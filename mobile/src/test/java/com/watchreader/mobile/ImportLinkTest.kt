package com.watchreader.mobile

import com.watchreader.mobile.data.repository.BookRepository
import com.watchreader.mobile.ui.SharedIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportLinkTest {
    @Test
    fun plainHttpAndBareAddressesAreAskedForOverHttps() {
        assertEquals("https://www.gutenberg.org/ebooks/1342.txt.utf-8", BookRepository.secureAddress("http://www.gutenberg.org/ebooks/1342.txt.utf-8"))
        assertEquals("https://Example.com/a.txt", BookRepository.secureAddress("  HTTP://Example.com/a.txt "))
        assertEquals("https://example.com/a.epub", BookRepository.secureAddress("example.com/a.epub"))
        assertEquals("https://example.com:8080/a.txt", BookRepository.secureAddress("example.com:8080/a.txt"))
        assertEquals("https://example.com/a.txt", BookRepository.secureAddress("//example.com/a.txt"))
        assertEquals("https://example.com/a.txt", BookRepository.secureAddress("https://example.com/a.txt"))
        // Other schemes are left for the download to refuse by name.
        assertEquals("ftp://example.com/a.txt", BookRepository.secureAddress("ftp://example.com/a.txt"))
    }

    @Test
    fun aTitleTakenFromALinkIsDecoded() {
        assertEquals("My Book", BookRepository.titleFromPath("/files/My%20Book.epub"))
        assertEquals("红楼梦", BookRepository.titleFromPath("/%E7%BA%A2%E6%A5%BC%E6%A2%A6.txt"))
        assertEquals("C++ Primer", BookRepository.titleFromPath("/C++%20Primer.txt"))
        assertEquals("Untitled", BookRepository.titleFromPath("/"))
        // A stray percent sign is kept rather than losing the name.
        assertEquals("100% true", BookRepository.titleFromPath("/100% true.txt"))
        // A zipped FB2 book loses both of its extensions.
        assertEquals("Tolstoy. War and Peace", BookRepository.titleFromPath("/get/Tolstoy.%20War%20and%20Peace.fb2.zip"))
        assertEquals("book.v2", BookRepository.titleFromPath("/book.v2.zip"))
    }

    @Test
    fun aLinkIsFoundInSharedText() {
        assertEquals("https://example.com/book.epub", SharedIntent.link("https://example.com/book.epub"))
        assertEquals("https://example.com/b.txt", SharedIntent.link("Pride and Prejudice\nhttps://example.com/b.txt"))
        assertEquals("http://example.com/b.txt", SharedIntent.link("Get it at http://example.com/b.txt."))
        assertEquals("https://en.wikipedia.org/wiki/Emma_(novel)", SharedIntent.link("(see https://en.wikipedia.org/wiki/Emma_(novel))"))
        assertNull(SharedIntent.link("A sentence someone selected, with no link in it."))
        assertNull(SharedIntent.link(null))
    }
}
