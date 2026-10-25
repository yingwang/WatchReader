package com.watchreader.mobile

import androidx.compose.runtime.saveable.SaverScope
import com.watchreader.mobile.highlight.TextSpan
import com.watchreader.mobile.ui.screen.NoteEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

class NoteEditorSaverTest {
    private val scope = SaverScope { true }

    /** Through a Bundle's way of keeping a list: written out and read back, as a recreated activity gets it. */
    private fun roundTrip(editor: NoteEditor?): NoteEditor? {
        val saved = with(NoteEditor.Saver) { scope.save(editor) } ?: return null
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(saved as Serializable) } }.toByteArray()
        @Suppress("UNCHECKED_CAST")
        val read = ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() } as ArrayList<Any?>
        return NoteEditor.Saver.restore(read)
    }

    @Test fun aNoteOnSelectedWordsComesBackWithItsWords() {
        val editor = NoteEditor(null, TextSpan(1_204, 1_230), "It is a truth universally acknowledged", "")
        assertEquals(editor, roundTrip(editor))
    }

    @Test fun aHighlightsNoteComesBackWithWhatItSaidBefore() {
        val editor = NoteEditor("h-42", null, "a single man in possession", "Irony from the first line.\nSee chapter 3.")
        assertEquals(editor, roundTrip(editor))
    }

    @Test fun noEditorSavesNothing() {
        assertNull(with(NoteEditor.Saver) { scope.save(null) })
        assertNull(roundTrip(null))
    }

    @Test fun somethingElseSavedUnderItsKeyIsNotTakenForANote() {
        assertNull(NoteEditor.Saver.restore(arrayListOf("h-1", 1, 2)))
        assertNull(NoteEditor.Saver.restore(arrayListOf(null, "x", 2, "quote", "")))
    }
}
