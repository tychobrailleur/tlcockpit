// TLCockpit
// Copyright 2017 Norbert Preining
// Licensed according to GPLv3+
//
// Front end for tlmgr

package TLCockpit

import javafx.collections.ObservableList
import javafx.scene.control

import TLCockpit.ApplicationMain.getClass
import TeXLive.{TLBackup, TLPackage, TLUpdate, TlmgrProcess}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, SyncVar}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Sorting, Success}
import scalafx.beans.Observable
import scalafx.beans.property.{ObjectProperty, ReadOnlyStringWrapper}
import scalafx.geometry.{HPos, Pos, VPos}
import scalafx.scene.control.Alert.AlertType
import scalafx.scene.image.{Image, ImageView}
import scalafx.scene.input.{KeyCode, KeyEvent}
// needed see https://github.com/scalafx/scalafx/issues/137
import scalafx.scene.control.TableColumn._
import scalafx.scene.control.Menu._
import scalafx.Includes._
import scalafx.application.{JFXApp, Platform}
import scalafx.application.JFXApp.PrimaryStage
import scalafx.geometry.Insets
import scalafx.scene.Scene
import scalafx.scene.layout._
import scalafx.scene.control._
import scalafx.event.ActionEvent
import scalafx.collections.ObservableBuffer
import scala.sys.process._
import scalafx.scene.text._
import scalafx.collections.ObservableBuffer


object ApplicationMain extends JFXApp {

  val version: String = getClass.getPackage.getImplementationVersion

  // necessary action when Window is closed with X or some other operation
  override def stopApp(): Unit = {
    tlmgr.cleanup()
  }

  val iconImage = new Image(getClass.getResourceAsStream("tlcockpit-48.jpg"))
  val logoImage = new Image(getClass.getResourceAsStream("tlcockpit-128.jpg"))

  val pkgs = scala.collection.mutable.Map.empty[String, TLPackage]

  val errorText: ObservableBuffer[String] = ObservableBuffer[String]()
  val outputText: ObservableBuffer[String] = ObservableBuffer[String]()

  val outputfield: TextArea = new TextArea {
    editable = false
    wrapText = true
    text = ""
  }
  val errorfield: TextArea = new TextArea {
    editable = false
    wrapText = true
    text = ""
  }
  errorText.onChange({
    errorfield.text = errorText.mkString("\n")
    errorfield.scrollTop = Double.MaxValue
  })
  outputText.onChange({
    outputfield.text = outputText.mkString("\n")
    outputfield.scrollTop = Double.MaxValue
  })

  val update_all_menu: MenuItem = new MenuItem("Update all") {
    onAction = (ae) => callback_update_all()
    disable = true
  }
  val update_self_menu: MenuItem = new MenuItem("Update self") {
    onAction = (ae) => callback_update_self()
    disable = true
  }

  val outerrtabs: TabPane = new TabPane {
    minWidth = 400
    tabs = Seq(
      new Tab {
        text = "Output"
        closable = false
        content = outputfield
      },
      new Tab {
        text = "Error"
        closable = false
        content = errorfield
      }
    )
  }
  val outerrpane: TitledPane = new TitledPane {
    text = "Debug"
    collapsible = true
    expanded = false
    content = outerrtabs
  }

  val cmdline = new TextField()
  cmdline.onKeyPressed = {
    (ae: KeyEvent) => if (ae.code == KeyCode.Enter) callback_run_cmdline()
  }
  val tlmgr = new TlmgrProcess(
    // (s:String) => outputfield.text = s,
    (s: Array[String]) => {
      // we don't wont normal output to be displayed
      // as it is anyway returned
      // outputText.clear()
      // outputText.appendAll(s)
    },
    (s: String) => {
      // errorText.clear()
      errorText.append(s)
      if (s != "") {
        outerrpane.expanded = true
        outerrtabs.selectionModel().select(1)
      }
    })

  def tlmgr_async_command(s: String, f: Array[String] => Unit): Unit = {
    errorText.clear()
    outputText.clear()
    outerrpane.expanded = false
    statusMenu.text = "Status: Busy"
    val foo = Future {
      tlmgr.send_command(s)
    }
    foo onComplete {
      case Success(ret) =>
        f(ret)
        Platform.runLater { statusMenu.text = "Status: Idle" }
      case Failure(t) =>
        errorText.append("An ERROR has occurred calling tlmgr: " + t.getMessage)
        outerrpane.expanded = true
        outerrtabs.selectionModel().select(1)
        Platform.runLater { statusMenu.text = "Status: Idle" }
    }
  }

  def update_pkg_lists(): Unit = {
    tlmgr_async_command("info --data name,localrev,remoterev,shortdesc,size,installed", (s: Array[String]) => {
      pkgs.clear()
      s.map((line: String) => {
        val fields: Array[String] = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1)
        val sd = fields(3)
        val shortdesc = if (sd.isEmpty) "" else sd.substring(1).dropRight(1).replace("""\"""",""""""")
        val inst = if (fields(5) == "1") "Installed" else "Not installed"
        val tlpkg = new TLPackage(fields(0), fields(1), fields(2), shortdesc, fields(4), inst)
        pkgs += ((fields(0), tlpkg))
      })
      val pkgbuf: ArrayBuffer[TLPackage] = ArrayBuffer.empty[TLPackage]
      val binbuf = scala.collection.mutable.Map.empty[String, ArrayBuffer[TLPackage]]
      pkgs.foreach(pkg => {
        // complicated part, determine whether it is a sub package or not!
        // we strip of initial texlive. prefixes to make sure we deal
        // with real packages
        if (pkg._1.stripPrefix("texlive.").contains(".")) {
          val foo: Array[String] = pkg._1.stripPrefix("texlive.infra").split('.')
          val pkgname = foo(0)
          val binname = foo(1)
          if (binbuf.keySet.contains(pkgname)) {
            binbuf(pkgname) += pkg._2
          } else {
            binbuf(pkgname) = ArrayBuffer[TLPackage](pkg._2)
          }
        } else {
          pkgbuf += pkg._2
        }
      })
      // now we have all normal packages in pkgbuf, and its sub-packages in binbuf
      // we need to create TreeItems
      val viewkids: ArrayBuffer[TreeItem[TLPackage]] = pkgbuf.map { p => {
          val kids: Seq[TLPackage] = if (binbuf.keySet.contains(p.name.value)) {
            binbuf(p.name.value)
          } else {
            Seq()
          }
          // for ismixed we && all the installed status. If all are installed, we get true
          val allinstalled = (kids :+ p).foldRight[Boolean](true)((k,b) => k.installed.value == "Installed" && b)
          if (!allinstalled) {
            // replace installed status with "Mixed"
            new TreeItem[TLPackage](new TreeItem[TLPackage](
              new TLPackage(p.name.value, p.lrev.value.toString, p.rrev.value.toString, p.shortdesc.value, p.size.value.toString, "Mixed")
            )) {
              children = kids.map(new TreeItem[TLPackage](_))
            }
          } else {
            new TreeItem[TLPackage](p) {
              children = kids.map(new TreeItem[TLPackage](_))
            }
          }
        }}
      Platform.runLater {
        packageTable.root = new TreeItem[TLPackage](new TLPackage("root","0","0","","0","")) {
          expanded = true
          children = viewkids.sortBy(_.value.value.name.value)
        }
      }
    })
  }

  def callback_quit(): Unit = {
    tlmgr.cleanup()
    Platform.exit()
    sys.exit(0)
  }

  def callback_run_text(s: String): Unit = {
    tlmgr_async_command(s, (s: Array[String]) => {})
  }

  def callback_run_cmdline(): Unit = {
    tlmgr_async_command(cmdline.text.value, s => {
      outputText.appendAll(s)
      outerrpane.expanded = true
      outerrtabs.selectionModel().select(0)
    })
  }

  def not_implemented_info(): Unit = {
    new Alert(AlertType.Warning) {
      initOwner(stage)
      title = "Warning"
      headerText = "This functionality is not implemented by now!"
      contentText = "Sorry for the inconveniences."
    }.showAndWait()
  }

  def callback_run_external(s: String): Unit = {
    outputText.clear()
    errorText.clear()
    outerrpane.expanded = true
    outerrtabs.selectionModel().select(0)
    outputText.append(s"Running $s")
    val foo = Future {
      s ! ProcessLogger(
        line => outputText.append(line),
        line => errorText.append(line)
      )
    }
    foo.onComplete {
      case Success(ret) =>
        outputText.append("Completed")
        outputfield.scrollTop = Double.MaxValue
      case Failure(t) =>
        errorText.append("An ERROR has occurred running $s: " + t.getMessage)
        errorfield.scrollTop = Double.MaxValue
        outerrpane.expanded = true
        outerrtabs.selectionModel().select(1)
    }
  }

  def callback_about(): Unit = {
    new Alert(AlertType.Information) {
      initOwner(stage)
      title = "About TLCockpit"
      graphic = new ImageView(logoImage)
      headerText = "TLCockpit version " + version + "\n\nManage your TeX Live with speed!"
      contentText = "Copyright 2017 Norbert Preining\nLicense: GPL3+\nSources: https://github.com/TeX-Live/tlcockpit"
    }.showAndWait()
  }

  def callback_update_all(): Unit = {
    // TODO parse table and update line by line
    tlmgr_async_command("update --all", _ => { Platform.runLater { update_pkg_lists() } })
  }

  def callback_update_self(): Unit = {
    // TODO should we restart tlmgr here - it might be necessary!!!
    tlmgr_async_command("update --self", _ => { Platform.runLater { update_pkg_lists() } })
  }

  def do_one_pkg(what: String, pkg: String): Unit = {
    tlmgr_async_command(s"$what $pkg", _ => { Platform.runLater { update_pkg_lists() } })
  }

  def callback_restore_pkg(str: String, rev: String): Unit = {
    not_implemented_info()
  }

  def callback_populate_backup_tab(): Unit = {
    // only run the restore command once
    if (backupTable.root.value.children.length == 0) {

      tlmgr_async_command("restore", lines => {
        // lines.drop(1).foreach(println(_))
        val foo = lines.drop(1).map { l =>
          val fields = l.split("[ ():]", -1).filter(_.nonEmpty)
          val pkgname = fields(0)
          val rests: Array[Array[String]] = fields.drop(1).sliding(4, 4).toArray
          if (rests.length == 1) {
            val p = rests(0)
            new TreeItem[TLBackup](new TLBackup(pkgname, p(0), p(1) + " " + p(2) + ":" + p(3)))
          } else {
            new TreeItem[TLBackup](new TLBackup(pkgname, "", "")) {
              children = rests.map(p => new TreeItem[TLBackup](
                new TLBackup(pkgname, p(0), p(1) + " " + p(2) + ":" + p(3))
              )).toSeq
            }
          }
        }
        val newroot = new TreeItem[TLBackup](new TLBackup("root", "", "")) {
          children = foo.sortBy(_.value.value.name.value)
        }
        Platform.runLater {
          backupTable.root = newroot
        }
      })
    }
  }

  def callback_populate_update_tab(): Unit = {
    // only run the restore command once
    if (updateTable.root.value.children.length == 0) {

      tlmgr_async_command("update --list", lines => {
        // lines.drop(1).foreach(println(_))
        var updatesAvailable = false
        var infraAvailable = false
        val foo = lines.filter { l =>
          l match {
            case u if u.startsWith("location-url") => false
            case u if u.startsWith("total-bytes") => false
            case u if u.startsWith("end-of-header") => false
            case u if u.startsWith("end-of-updates") => false
            case u => true
          }
        }.map { l => {
          val fields = l.split("\t")
          val pkgname = fields(0)
          val status  = fields(1) match {
            case "d" => "Removed on server"
            case "f" => "Forcibly removed"
            case "u" => "Update available"
            case "r" => "Local is newer"
            case "a" => "New on server"
            case "i" => "Not installed"
            case "I" => "Reinstall"
          }
          val localrev = fields(2)
          val serverrev = fields(3)
          val size = humanReadableByteSize(fields(4).toLong)
          val runtime = fields(5)
          val esttot = fields(6)
          val tag = fields(7)
          val lctanv = fields(8)
          val rctanv = fields(9)
          val tlpkg: TLPackage = pkgs(pkgname)
          val shortdesc = tlpkg.shortdesc.value
          if (pkgname.startsWith("texlive.infra"))
            infraAvailable = true
          else
            updatesAvailable = true
          new TreeItem[TLUpdate](new TLUpdate(pkgname,status,
                                              localrev + { if (lctanv != "-") s" ($lctanv)" else "" },
                                              serverrev + { if (rctanv != "-") s" ($rctanv)" else "" },
                                              shortdesc, size))
        }}
        val newroot = new TreeItem[TLUpdate](new TLUpdate("root", "", "", "", "", "")) {
          children = foo.sortBy(_.value.value.name.value)
        }
        Platform.runLater {
          update_self_menu.disable = !infraAvailable
          update_all_menu.disable = !updatesAvailable
          updateTable.root = newroot
        }
      })
    }
  }
  def callback_show_pkg_info(pkg: String): Unit = {
    tlmgr_async_command(s"info $pkg", pkginfo => {
      // need to call runLater to go back to main thread!
      Platform.runLater {
        val dialog = new Dialog() {
          initOwner(stage)
          title = s"Package Information for $pkg"
          headerText = s"Package Information for $pkg"
          resizable = true
        }
        dialog.dialogPane().buttonTypes = Seq(ButtonType.OK)
        val grid = new GridPane() {
          hgap = 10
          vgap = 10
          padding = Insets(20)
        }
        var crow = 0
        pkginfo.foreach((line: String) => {
          val keyval = line.split(":", 2).map(_.trim)
          if (keyval.length == 2) {
            val keylabel = new Label(keyval(0))
            val vallabel = new Label(keyval(1))
            vallabel.wrapText = true
            grid.add(keylabel, 0, crow)
            grid.add(vallabel, 1, crow)
            crow += 1
          }
        })
        grid.columnConstraints = Seq(new ColumnConstraints(100, 150, 200), new ColumnConstraints(100, 300, 5000, Priority.Always, new HPos(HPos.Left), true))
        dialog.dialogPane().content = grid
        dialog.width = 500
        dialog.showAndWait()
      }
    })
  }

  /**
    * @see https://stackoverflow.com/questions/3263892/format-file-size-as-mb-gb-etc
    * @see https://en.wikipedia.org/wiki/Zettabyte
    * @param fileSize Up to Exabytes
    * @return
    */
  def humanReadableByteSize(fileSize: Long): String = {
    if(fileSize <= 0) return "0 B"
    // kilo, Mega, Giga, Tera, Peta, Exa, Zetta, Yotta
    val units: Array[String] = Array("B", "kB", "MB", "GB", "TB", "PB", "EB", "ZB", "YB")
    val digitGroup: Int = (Math.log10(fileSize)/Math.log10(1024)).toInt
    f"${fileSize/Math.pow(1024, digitGroup)}%3.1f ${units(digitGroup)}"
  }

  val mainMenu = new Menu("TLCockpit") {
    items = List(
      update_all_menu,
      update_self_menu,
      new SeparatorMenuItem,
      new MenuItem("Update filename database ...") {
        onAction = (ae) => callback_run_external("mktexlsr")
      },
      // calling fmtutil kills JavaFX when the first sub-process (format) is called
      // I get loads of Exception in thread "JavaFX Application Thread" java.lang.ArrayIndexOutOfBoundsException
      // new MenuItem("Rebuild all formats ...") { onAction = (ae) => callback_run_external("fmtutil --sys --all") },
      new MenuItem("Update font map database ...") {
        onAction = (ae) => callback_run_external("updmap --sys")
      },
      /*
      new MenuItem("Restore packages from backup ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      new MenuItem("Handle symlinks in system dirs ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      new SeparatorMenuItem,
      new MenuItem("Remove TeX Live ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      */
      new SeparatorMenuItem,
      // temporarily move here as we disable the Help menu
      new MenuItem("About") {
        onAction = (ae) => callback_about()
      },
      new MenuItem("Exit") {
        onAction = (ae: ActionEvent) => callback_quit()
      })
  }
  val optionsMenu = new Menu("Options") {
    items = List(
      new MenuItem("General ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      new MenuItem("Paper ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      new MenuItem("Platforms ...") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      new SeparatorMenuItem,
      new CheckMenuItem("Expert options") {
        disable = true
      },
      new CheckMenuItem("Enable debugging options") {
        disable = true
      },
      new CheckMenuItem("Disable auto-install of new packages") {
        disable = true
      },
      new CheckMenuItem("Disable auto-removal of server-deleted packages") {
        disable = true
      }
    )
  }
  val helpMenu = new Menu("Help") {
    items = List(
      /*
      new MenuItem("Manual") {
        disable = true; onAction = (ae) => not_implemented_info()
      },
      */
      new MenuItem("About") {
        onAction = (ae) => callback_about()
      },
    )
  }
  val statusMenu = new Menu("Status: Idle") {
    disable = true
  }
  val expertPane = new TitledPane {
    text = "Experts only"
    collapsible = true
    expanded = false
    content = new VBox {
      spacing = 10
      children = List(
        new HBox {
          spacing = 10
          alignment = Pos.CenterLeft
          children = List(
            new Label("tlmgr shell command:"),
            cmdline,
            new Button {
              text = "Go"
              onAction = (event: ActionEvent) => callback_run_cmdline()
            }
          )
        },
        /* new HBox {
          spacing = 10
          children = List(
            new Button {
              text = "Show updates"
              onAction = (e: ActionEvent) => callback_show_updates()
            },
            new Button {
              text = "Show installed"
              onAction = (e: ActionEvent) => callback_show_installed()
            },
            new Button {
              text = "Show all"
              onAction = (e: ActionEvent) => callback_show_all()
            },
            update_self_button,
            update_all_button
          )
        }
        */
      )
    }
  }
  val updateTable = {
    val colName = new TreeTableColumn[TLUpdate, String] {
      text = "Package"
      cellValueFactory = { _.value.value.value.name }
      prefWidth = 150
    }
    val colStatus = new TreeTableColumn[TLUpdate, String] {
      text = "Status"
      cellValueFactory = { _.value.value.value.status }
      prefWidth = 120
    }
    val colDesc = new TreeTableColumn[TLUpdate, String] {
      text = "Description"
      cellValueFactory = { _.value.value.value.shortdesc }
      prefWidth = 300
    }
    val colLRev = new TreeTableColumn[TLUpdate, String] {
      text = "Local rev"
      cellValueFactory = { _.value.value.value.lrev }
      prefWidth = 100
    }
    val colRRev = new TreeTableColumn[TLUpdate, String] {
      text = "Remote rev"
      cellValueFactory = { _.value.value.value.rrev }
      prefWidth = 100
    }
    val colSize = new TreeTableColumn[TLUpdate, String] {
      text = "Size"
      cellValueFactory = { _.value.value.value.size }
      prefWidth = 70
    }
    val table = new TreeTableView[TLUpdate](
      new TreeItem[TLUpdate](new TLUpdate("root","","","","","")) {
        expanded = false
      }) {
      columns ++= List(colName, colStatus, colDesc, colLRev, colRRev, colSize)
    }
    colDesc.prefWidth.bind(table.width - colName.width - colLRev.width - colRRev.width - colSize.width - colStatus. width - 15)
    table.prefHeight = 300
    table.vgrow = Priority.Always
    table.placeholder = new Label("No updates available")
    table.showRoot = false
    table.rowFactory = { _ =>
      val row = new TreeTableRow[TLUpdate] {}
      val ctm = new ContextMenu(
        new MenuItem("Info") {
          onAction = (ae) => callback_show_pkg_info(row.item.value.name.value)
        },
        new MenuItem("Install") {
          // onAction = (ae) => callback_run_text("install " + row.item.value.name.value)
          onAction = (ae) => do_one_pkg("install", row.item.value.name.value)
        },
        new MenuItem("Remove") {
          // onAction = (ae) => callback_run_text("remove " + row.item.value.name.value)
          onAction = (ae) => do_one_pkg("remove", row.item.value.name.value)
        },
        new MenuItem("Update") {
          // onAction = (ae) => callback_run_text("update " + row.item.value.name.value)
          onAction = (ae) => do_one_pkg("update", row.item.value.name.value)
        }
      )
      row.contextMenu = ctm
      row
    }
    table
  }
  val packageTable = {
    val colName = new TreeTableColumn[TLPackage, String] {
      text = "Package"
      cellValueFactory = {  _.value.value.value.name }
      prefWidth = 150
    }
    /*
    val colLRev = new TableColumn[TLPackage, String] {
      text = "Local rev"
      cellValueFactory = { _.value.lrev }
      prefWidth = 100
    }
    val colRRev = new TableColumn[TLPackage, String] {
      text = "Remote rev"
      cellValueFactory = { _.value.rrev }
      prefWidth = 100
    }
    */
    val colDesc = new TreeTableColumn[TLPackage, String] {
      text = "Description"
      cellValueFactory = { _.value.value.value.shortdesc }
      prefWidth = 300
    }
    val colInst = new TreeTableColumn[TLPackage, String] {
      text = "Installed"
      cellValueFactory = { _.value.value.value.installed  }
      prefWidth = 100
    }
    val table = new TreeTableView[TLPackage](
      new TreeItem[TLPackage](new TLPackage("root","0","0","","0","")) {
        expanded = false
      }) {
      columns ++= List(colName, colDesc, colInst)
    }
    colDesc.prefWidth.bind(table.width - colInst.width - colName.width - 15)
    table.prefHeight = 300
    table.showRoot = false
    table.vgrow = Priority.Always
    table.rowFactory = { _ =>
      val row = new TreeTableRow[TLPackage] {}
      val ctm = new ContextMenu(
        new MenuItem("Info") {
          onAction = (ae) => callback_show_pkg_info(row.item.value.name.value)
        },
        new MenuItem("Install") {
          onAction = (ae) => do_one_pkg("install", row.item.value.name.value)
        },
        new MenuItem("Remove") {
          onAction = (ae) => do_one_pkg("remove", row.item.value.name.value)
        },
        new MenuItem("Update") {
          onAction = (ae) => do_one_pkg("update", row.item.value.name.value)
        }
      )
      row.contextMenu = ctm
      row
    }
    table
  }
  val backupTable = {
    val colName = new TreeTableColumn[TLBackup, String] {
      text = "Package"
      cellValueFactory = {  _.value.value.value.name }
      prefWidth = 150
    }
    val colRev = new TreeTableColumn[TLBackup, String] {
      text = "Revision"
      cellValueFactory = { _.value.value.value.rev }
      prefWidth = 100
    }
    val colDate = new TreeTableColumn[TLBackup, String] {
      text = "Date"
      cellValueFactory = { _.value.value.value.date }
      prefWidth = 300
    }
    val table = new TreeTableView[TLBackup](
      new TreeItem[TLBackup](new TLBackup("root","","")) {
        expanded = false
      }) {
      columns ++= List(colName, colRev, colDate)
    }
    colDate.prefWidth.bind(table.width - colRev.width - colName.width - 15)
    table.prefHeight = 300
    table.showRoot = false
    table.vgrow = Priority.Always
    table.rowFactory = { _ =>
      val row = new TreeTableRow[TLBackup] {}
      val ctm = new ContextMenu(
        new MenuItem("Info") {
          onAction = (ae) => callback_show_pkg_info(row.item.value.name.value)
        },
        new MenuItem("Restore") {
          onAction = (ae) => callback_restore_pkg(row.item.value.name.value, row.item.value.rev.value)
        }
      )
      row.contextMenu = ctm
      row
    }
    table
  }
  val pkgstabs = new TabPane {
    minWidth = 400
    vgrow = Priority.Always
    tabs = Seq(
      new Tab {
        text = "Packages"
        closable = false
        content = packageTable
      },
      new Tab {
        text = "Updates"
        closable = false
        content = updateTable
      },
      new Tab {
        text = "Backups"
        closable = false
        content = backupTable
      }
    )
  }
  pkgstabs.selectionModel().selectedItem.onChange(
    (a,b,c) => {
      if (a.value.text() == "Backups") {
        callback_populate_backup_tab()
      } else if (a.value.text() == "Updates") {
        callback_populate_update_tab()
        // println("Entering Backup Tab")
      }
    }
  )
  val menuBar = new MenuBar {
    useSystemMenuBar = true
    // menus.addAll(mainMenu, optionsMenu, helpMenu)
    menus.addAll(mainMenu, statusMenu)
  }

  stage = new PrimaryStage {
    title = "TLCockpit"
    scene = new Scene {
      root = {
        // val topBox = new HBox {
        //   children = List(menuBar, statusLabel)
        // }
        // topBox.hgrow = Priority.Always
        // topBox.maxWidth = Double.MaxValue
        val topBox = menuBar
        val centerBox = new VBox {
          padding = Insets(10)
          children = List(pkgstabs, expertPane, outerrpane)
        }
        new BorderPane {
          // padding = Insets(20)
          top = topBox
          // left = leftBox
          center = centerBox
          // bottom = bottomBox
        }
      }
    }
    icons.add(iconImage)
  }

  /* TODO implement splash screen - see example in ProScalaFX
  val startalert = new Alert(AlertType.Information)
  startalert.setTitle("Loading package database ...")
  startalert.setContentText("Loading package database, this might take a while. Please wait!")
  startalert.show()
  */

  /*
  var testmode = false
  if (parameters.unnamed.nonEmpty) {
    if (parameters.unnamed.head == "-test" || parameters.unnamed.head == "--test") {
      println("Testing mode enabled, not actually calling tlmgr!")
      testmode = true
    }
  }
  */

  tlmgr.start_process()
  tlmgr.get_output_till_prompt()
  update_pkg_lists() // this is async done

}  // object ApplicationMain

// vim:set tabstop=2 expandtab : //
