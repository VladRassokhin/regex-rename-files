package nl.bryanderidder.regexrenamefiles

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages.showErrorDialog
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.rename.RenameProcessor
import nl.bryanderidder.regexrenamefiles.icons.RegexRenameFilesIcons.ActionIcon
import java.io.IOException

/**
 * @author Bryan de Ridder
 */
class ReplaceFileNamesAction : DumbAwareAction(ActionIcon) {

    override fun update(event: AnActionEvent) {
        val selectedFiles = event.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabledAndVisible = event.project != null &&
                selectedFiles != null && when (selectedFiles.size) {
            0 -> false
            1 -> selectedFiles.first().children?.isNotEmpty() ?: false
            else -> true
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val selectedFiles = event.getData(PlatformDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: return
        val project = event.project ?: return
        val dialog = ReplaceFileNamesDialogWrapper(selectedFiles)
        val groupId = "ReplaceFileNamesAction"
        if (dialog.showAndGet()) {
            val events: List<RenameEvent> = ReadAction.compute<List<RenameEvent>, Throwable> {
                prepareFilesRename(dialog, project)
            } ?: return
            val rest = if (dialog.isUseRenameRefactoring()) {
                renameUsingRefactoring(project, events)
            } else {
                events
            }
            if (rest.isNotEmpty()) {
                WriteCommandAction.runWriteCommandAction(project, ReplaceFileNamesDialogWrapper.TITLE, groupId, {
                    renameFiles(project, rest)
                })
            }
        }
    }

    private fun prepareFilesRename(dialog: ReplaceFileNamesDialogWrapper, project: Project): List<RenameEvent>? {
        val events = dialog.getFiles().mapNotNull { file ->
            val oldName = file.name
            val newName = createNewFileName(dialog, oldName)
            if (newName != oldName) RenameEvent(file, oldName, newName) else null
        }
        if (events.isEmpty()) return null
        val errors = validate(events)
        if (errors.isNotEmpty()) {
            val errorMessage = "Cannot rename ${errors.size} ${StringUtil.pluralize("file", errors.size)}:\n${errors.joinToString("\n")}"
            LOGGER.warn(errorMessage)
            invokeLater {
                showErrorDialog(project, errorMessage, ReplaceFileNamesDialogWrapper.TITLE)
            }
            return null
        }
        return events
    }

    private fun renameUsingRefactoring(project: Project, events: List<RenameEvent>): List<RenameEvent> {
        val rest = ArrayList<RenameEvent>()
        val psiManager = PsiManager.getInstance(project)
        var processor: RenameProcessor? = null

        if (DumbService.isDumb(project)) {
            // Rename Refactoring doesn't work in dumb mode, fallback to VirtualFile rename
            return events
        }
        for (event in events) {
            val file = event.file
            val psiFSItem: PsiFileSystemItem? = if (file.isDirectory) {
                psiManager.findDirectory(file)
            } else {
                psiManager.findFile(file)
            }
            if (psiFSItem != null) {
                if (processor == null) {
                    processor = RenameProcessor(project, psiFSItem, event.newName, GlobalSearchScope.projectScope(project), true, true)
                    processor.setCommandName(ReplaceFileNamesDialogWrapper.TITLE)
                } else {
                    processor.addElement(psiFSItem, event.newName)
                }
            } else {
                rest.add(event)
            }
        }
        if (DumbService.isDumb(project)) {
            // Rename Refactoring doesn't work in dumb mode, fallback to VirtualFile rename
            return events
        }
        processor?.run()
        return rest
    }

    private fun renameFiles(project: Project, events: List<RenameEvent>) {
        val performedEvents = ArrayList<RenameEvent>()
        for (renameEvent in events) {
            try {
                renameEvent.file.rename(this, renameEvent.newName)
                performedEvents.add(renameEvent)
            } catch (e: IOException) {
                val errorMessage = "Cannot rename file '${renameEvent.file.name}', rolled back other renames. Error: ${e.message}"
                LOGGER.warn(errorMessage, e)
                revertRenameFiles(performedEvents)
                invokeLater {
                    showErrorDialog(project, errorMessage, ReplaceFileNamesDialogWrapper.TITLE)
                }
                return
            }
        }
    }

    private fun validate(events: List<RenameEvent>): List<String> {
        val errors = ArrayList<String>()
        for (fs in events.map { it.file.fileSystem }.toSet()) {
            if (fs.isReadOnly) {
                errors.add("File system containing one of files is read only")
            }
        }
        for (it in events) {
            if (!it.file.isValid) {
                errors.add("File '${it.file.name}' is not valid")
                continue
            }
            if (!it.file.fileSystem.isValidName(it.newName)) {
                errors.add("New file name '${it.newName}' is not supported by the file system")
                continue
            }
        }
        return errors
    }

    private fun revertRenameFiles(performedEvents: List<RenameEvent>) = performedEvents.forEach {
        it.file.rename(this, it.oldName)
    }

    private fun createNewFileName(dialog: ReplaceFileNamesDialogWrapper, oldName: String): String {
        val fromText = dialog.getReplaceFromText()
        val toText = dialog.getReplaceToText()
        return when {
            dialog.isUseRegex() -> when {
                dialog.isLowerCase() ->
                    oldName.replace(fromText.toRegex(), toText).lowercase()

                else -> oldName.replace(fromText.toRegex(), toText)
            }

            else -> when {
                dialog.isLowerCase() ->
                    oldName.replace(fromText, toText).lowercase()

                else -> oldName.replace(fromText, toText)
            }
        }
    }

    companion object {
        private val LOGGER: Logger = Logger.getInstance(ReplaceFileNamesAction::class.java)
    }
}
