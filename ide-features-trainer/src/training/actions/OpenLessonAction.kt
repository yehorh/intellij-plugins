package training.actions

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import training.lang.LangManager
import training.learn.CourseManager
import training.learn.LearnBundle
import training.learn.Module
import training.learn.NewLearnProjectUtil
import training.learn.dialogs.SdkModuleProblemDialog
import training.learn.exceptons.*
import training.learn.lesson.Lesson
import training.learn.lesson.LessonProcessor
import training.learn.lesson.listeners.NextLessonListener
import training.learn.lesson.listeners.StatisticLessonListener
import training.ui.LearnToolWindowFactory
import training.ui.UiManager
import training.util.findLanguageByID
import training.util.getCurrentProject
import java.awt.FontFormatException
import java.io.IOException
import java.util.concurrent.ExecutionException

/**
 * @author Sergey Karashevich
 */
class OpenLessonAction : AnAction() {

  override fun actionPerformed(e: AnActionEvent) {

    val lesson = e.getData(LESSON_DATA_KEY)
    val project = e.project

    if (lesson != null && project != null) {
      openLesson(project, lesson)
    } else {
      //in case of starting from Welcome Screen
      val myLearnProject = initLearnProject(project)
      openLearnToolWindowAndShowModules(myLearnProject!!)
    }
  }

  @Synchronized @Throws(BadModuleException::class, BadLessonException::class, IOException::class, FontFormatException::class, InterruptedException::class, ExecutionException::class, LessonIsOpenedException::class)
  private fun openLesson(project: Project, lesson: Lesson) {
    try {
      val langSupport = LangManager.getInstance().getLangSupport()
      if (lesson.isOpen) throw LessonIsOpenedException(lesson.name + " is opened")

      //If lesson doesn't have parent module
      if (lesson.module == null)
        throw BadLessonException("Unable to open lesson without specified module")

      val scratchFileName = "Learning"
      val vf: VirtualFile?
      val learnProject = CourseManager.instance.learnProject
      if (lesson.module == null || lesson.module!!.moduleType == Module.ModuleType.SCRATCH) {
        CourseManager.instance.checkEnvironment(project)
        vf = getScratchFile(project, lesson, scratchFileName)
      } else {
        //0. learnProject == null but this project is LearnProject then just getFileInLearnProject
        if (learnProject == null && getCurrentProject()!!.name == langSupport.defaultProjectName) {
          CourseManager.instance.learnProject = getCurrentProject()
          vf = getFileInLearnProject(lesson)
          //1. learnProject == null and current project has different name then initLearnProject and register post startup open lesson
        } else if (learnProject == null && getCurrentProject()!!.name != langSupport.defaultProjectName) {
          val myLearnProject = initLearnProject(project) ?: return
          // in case of user aborted to create a LearnProject
          openLessonWhenLearnProjectStart(lesson, myLearnProject)
          return
          //2. learnProject != null and learnProject is disposed then reinitProject and getFileInLearnProject
        } else if (learnProject!!.isDisposed) {
          val myLearnProject = initLearnProject(project) ?: return
          // in case of user aborted to create a LearnProject
          openLessonWhenLearnProjectStart(lesson, myLearnProject)
          return
          //3. learnProject != null and learnProject is opened but not focused then focus Project and getFileInLearnProject
        } else if (learnProject.isOpen && getCurrentProject() != learnProject) {
          vf = getFileInLearnProject(lesson)
          //4. learnProject != null and learnProject is opened and focused getFileInLearnProject
        } else if (learnProject.isOpen && getCurrentProject() == learnProject) {
          vf = getFileInLearnProject(lesson)
        } else {
          throw Exception("Unable to start Learn project")
        }
      }

      val currentProject = if (lesson.module != null && lesson.module!!.moduleType != Module.ModuleType.SCRATCH) CourseManager.instance.learnProject!! else project
      if (vf == null) return  //if user aborts opening lesson in LearnProject or Virtual File couldn't be computed

      addNextLessonListenerIfNeeded(currentProject, lesson)
      addStatisticLessonListenerIfNeeded(currentProject, lesson)

      //open next lesson if current is passed
      UiManager.setLessonView()
      lesson.onStart()


      //to start any lesson we need to do 4 steps:
      //1. open editor or find editor
      var textEditor: TextEditor? = null
      if (FileEditorManager.getInstance(project).isFileOpen(vf)) {
        val editors = FileEditorManager.getInstance(project).getEditors(vf)
        for (fileEditor in editors) {
          if (fileEditor is TextEditor) {
            textEditor = fileEditor
          }
        }
      }
      if (textEditor == null) {
        val editors = FileEditorManager.getInstance(project).openFile(vf, true, true)
        for (fileEditor in editors) {
          if (fileEditor is TextEditor) {
            textEditor = fileEditor
          }
        }
      }
      if (textEditor!!.editor.isDisposed) throw Exception("Editor is already disposed!")

      //2. set the focus on this editor
      //FileEditorManager.getInstance(project).setSelectedEditor(vf, TextEditorProvider.getInstance().getEditorTypeId());
      FileEditorManager.getInstance(project).openEditor(OpenFileDescriptor(project, vf), true)

      //3. update tool window
      UiManager.learnPanel?.clear()

      //4. Process lesson
      LessonProcessor.process(project, lesson, textEditor.editor)

    } catch (noSdkException: NoSdkException) {
      Messages.showMessageDialog(project, LearnBundle.message("dialog.noSdk.message", LangManager.getInstance().getLanguageDisplayName()), LearnBundle.message("dialog.noSdk.title"), Messages.getErrorIcon())
      if (ProjectSettingsService.getInstance(project).chooseAndSetSdk() != null) openLesson(project, lesson)
    } catch (noSdkException: InvalidSdkException) {
      Messages.showMessageDialog(project, LearnBundle.message("dialog.noSdk.message", LangManager.getInstance().getLanguageDisplayName()), LearnBundle.message("dialog.noSdk.title"), Messages.getErrorIcon())
      if (ProjectSettingsService.getInstance(project).chooseAndSetSdk() != null) openLesson(project, lesson)
    } catch (noJavaModuleException: NoJavaModuleException) {
      showModuleProblemDialog(project)
    } catch (e: Exception) {
      e.printStackTrace()
    }

  }

  private fun addNextLessonListenerIfNeeded(currentProject: Project, lesson: Lesson) {
    val lessonListener = NextLessonListener(currentProject)
    if (!lesson.lessonListeners.any { it is NextLessonListener })
      lesson.addLessonListener(lessonListener)
  }

  private fun addStatisticLessonListenerIfNeeded(currentProject: Project, lesson: Lesson) {
    val statLessonListener = StatisticLessonListener(currentProject)
    if (!lesson.lessonListeners.any { it is StatisticLessonListener })
      lesson.addLessonListener(statLessonListener)
  }

  //
  private fun openLearnToolWindowAndShowModules(myLearnProject: Project) {
    if (myLearnProject.isOpen && myLearnProject.isInitialized) {
      showModules(myLearnProject)
    } else {
      StartupManager.getInstance(myLearnProject).registerPostStartupActivity { showModules(myLearnProject) }
    }
  }

  private fun showModules(project: Project) {
    val toolWindowManager = ToolWindowManager.getInstance(project)
    val learnToolWindow = toolWindowManager.getToolWindow(LearnToolWindowFactory.LEARN_TOOL_WINDOW)
    if (learnToolWindow != null) {
      learnToolWindow.show(null)
      try {
        UiManager.setModulesView()
      } catch (e: Exception) {
        e.printStackTrace()
      }

    }
  }

  private fun openLessonWhenLearnProjectStart(lesson: Lesson?, myLearnProject: Project) {
    StartupManager.getInstance(myLearnProject).registerPostStartupActivity {
      val toolWindowManager = ToolWindowManager.getInstance(myLearnProject)
      val learnToolWindow = toolWindowManager.getToolWindow(LearnToolWindowFactory.LEARN_TOOL_WINDOW)
      if (learnToolWindow != null) {
        learnToolWindow.show(null)
        try {
          UiManager.setLessonView()
          CourseManager.instance.openLesson(myLearnProject, lesson)
        } catch (e: Exception) {
          e.printStackTrace()
        }

      }
    }
  }

  @Throws(IOException::class)
  private fun getScratchFile(project: Project, lesson: Lesson?, filename: String): VirtualFile? {
    var vf: VirtualFile? = null
    assert(lesson != null)
    assert(lesson!!.module != null)
    val myLanguage = lesson.lang

    val languageByID = findLanguageByID(myLanguage)
    if (CourseManager.instance.mapModuleVirtualFile.containsKey(lesson.module)) {
      vf = CourseManager.instance.mapModuleVirtualFile[lesson.module]
      ScratchFileService.getInstance().scratchesMapping.setMapping(vf, languageByID)
    }
    if (vf == null || !vf.isValid) {
      //while module info is not stored

      //find file if it is existed
      vf = ScratchFileService.getInstance().findFile(ScratchRootType.getInstance(), filename, ScratchFileService.Option.existing_only)
      if (vf != null) {
        FileEditorManager.getInstance(project).closeFile(vf)
        ScratchFileService.getInstance().scratchesMapping.setMapping(vf, languageByID)
      }

      if (vf == null || !vf.isValid) {
        vf = ScratchRootType.getInstance().createScratchFile(project, filename, languageByID, "")
        assert(vf != null)
      }
      if (lesson.module != null) CourseManager.instance.registerVirtualFile(lesson.module!!, vf!!)
    }
    return vf
  }

  //
//    private fun showSdkProblemDialog(project: Project, sdkMessage: String) {
//        val dialog = SdkProjectProblemDialog(project, sdkMessage)
//        dialog.show()
//    }
//
  private fun showModuleProblemDialog(project: Project) {
    val dialog = SdkModuleProblemDialog(project)
    dialog.show()
  }

  //
  @Throws(IOException::class)
  private fun getFileInLearnProject(lesson: Lesson): VirtualFile {

    if (lesson.module == null) throw Exception("Error: cannot create learning file in project for a lesson (${lesson.name}) without module (or module is null)")
    val function = object : Computable<VirtualFile> {

      override fun compute(): VirtualFile {
        val learnProject = CourseManager.instance.learnProject!!
        val sourceRootFile = ProjectRootManager.getInstance(learnProject).contentSourceRoots[0]
        val myLanguage = lesson.lang
        val languageByID = findLanguageByID(myLanguage)
        val extensionFile = languageByID!!.associatedFileType!!.defaultExtension

        var fileName = "Test." + extensionFile
        if (lesson.module != null) {
          fileName = lesson.module!!.nameWithoutWhitespaces + "." + extensionFile
        }

        var lessonVirtualFile: VirtualFile? = null
        for (file in ProjectRootManager.getInstance(learnProject).contentSourceRoots){
            if (file.name == fileName){
                lessonVirtualFile = file
                break
            } else {
                lessonVirtualFile = file.findChild(fileName)
                if(lessonVirtualFile != null){
                    break
                }
            }
        }
        if (lessonVirtualFile == null) {
          try {
            lessonVirtualFile = sourceRootFile.createChildData(this, fileName)
          } catch (e: IOException) {
            e.printStackTrace()
          }

        }

        if (lesson.module != null) CourseManager.instance.registerVirtualFile(lesson.module!!, lessonVirtualFile!!)
        return lessonVirtualFile!!
      }
    }

    val vf = ApplicationManager.getApplication().runWriteAction(function)
    assert(vf is VirtualFile)
    return vf
  }

  //
  private fun initLearnProject(projectToClose: Project?): Project? {
    var myLearnProject: Project? = null
      val langSupport = LangManager.getInstance().getLangSupport()
    //if projectToClose is open
    val openProjects = ProjectManager.getInstance().openProjects
    for (openProject in openProjects) {
      val name = openProject.name
      if (name == langSupport.defaultProjectName) {
        myLearnProject = openProject
        if (ApplicationManager.getApplication().isUnitTestMode) return openProject
      }
    }

    if (myLearnProject == null || myLearnProject.projectFile == null) {

      if (!ApplicationManager.getApplication().isUnitTestMode && projectToClose != null)
        if (!NewLearnProjectUtil.showDialogOpenLearnProject(projectToClose))
          return null //if user abort to open lesson in a new Project
      if (CourseManager.instance.learnProjectPath != null) {
        try {
          myLearnProject = ProjectManager.getInstance().loadAndOpenProject(CourseManager.instance.learnProjectPath!!)
        } catch (e: Exception) {
          e.printStackTrace()
        }

      } else {
        try {
          myLearnProject = NewLearnProjectUtil.createLearnProject(projectToClose, langSupport)
        } catch (e: IOException) {
          e.printStackTrace()
        }

      }

      langSupport.applyToProjectAfterConfigure().invoke(myLearnProject!!)
    }

    CourseManager.instance.learnProject = myLearnProject

    assert(CourseManager.instance.learnProject != null)

    CourseManager.instance.learnProjectPath = CourseManager.instance.learnProject!!.basePath

    return myLearnProject

  }

  companion object {
    var LESSON_DATA_KEY = DataKey.create<Lesson>("LESSON_DATA_KEY")
  }


}
