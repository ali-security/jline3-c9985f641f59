/*
 * Copyright (c) 2023, the original author(s).
 *
 * This software is distributable under the BSD license. See the terms of the
 * BSD license in the documentation provided with this software.
 *
 * https://opensource.org/licenses/BSD-3-Clause
 */
package org.jline.reader.impl;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.impl.history.DefaultHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.jline.keymap.KeyMap.translate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class HistorySearchTest extends ReaderTestSupport {

    private DefaultHistory setupHistory() {
        DefaultHistory history = new DefaultHistory();
        reader.setVariable(LineReader.HISTORY_SIZE, 10);
        reader.setHistory(history);
        history.add("foo");
        history.add("fiddle");
        history.add("faddle");
        return history;
    }

    @Test
    public void testCaseInsensitive() throws Exception {
        setupHistory();
        reader.setOpt(LineReader.Option.CASE_INSENSITIVE_SEARCH);
        try {
            assertLine("fiddle", new TestBuffer().ctrl('R').append("I").enter(), false);
        } finally {
            reader.unsetOpt(LineReader.Option.CASE_INSENSITIVE_SEARCH);
        }
    }

    @Test
    public void testReverseHistorySearch() throws Exception {
        DefaultHistory history = setupHistory();

        // TODO: use assertBuffer
        String readLineResult;
        in.setIn(new ByteArrayInputStream(translate("^Rf\n").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("faddle", readLineResult);
        assertEquals(3, history.size());

        in.setIn(new ByteArrayInputStream(translate("^Rf^R^R^R^R^R\n").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("foo", readLineResult);
        assertEquals(4, history.size());

        in.setIn(new ByteArrayInputStream(translate("^Rf^R^R\n").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("fiddle", readLineResult);
        assertEquals(5, history.size());
    }

    @Test
    public void testForwardHistorySearch() throws Exception {
        DefaultHistory history = setupHistory();

        String readLineResult;
        in.setIn(new ByteArrayInputStream(translate("^Rf^R^R^S\n").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("fiddle", readLineResult);
        assertEquals(4, history.size());

        in.setIn(new ByteArrayInputStream(translate("^Rf^R^R^R^S^S\n").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("faddle", readLineResult);
        assertEquals(5, history.size());

        in.setIn(new ByteArrayInputStream(translate("^Rf^R^R^R^R^S\n").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("fiddle", readLineResult);
        assertEquals(6, history.size());
    }

    @Test
    public void testSearchHistoryAfterHittingEnd() throws Exception {
        DefaultHistory history = setupHistory();

        String readLineResult;
        in.setIn(new ByteArrayInputStream(translate("^Rf^R^R^R^S\n").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("fiddle", readLineResult);
        assertEquals(4, history.size());
    }

    @Test
    public void testSearchHistoryWithNoMatches() throws Exception {
        DefaultHistory history = setupHistory();

        String readLineResult;
        in.setIn(new ByteArrayInputStream(translate("x^S^S\n").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("x", readLineResult);
        assertEquals(4, history.size());
    }

    @Test
    public void testAbortingSearchRetainsCurrentBufferAndPrintsDetails() throws Exception {
        DefaultHistory history = setupHistory();

        in.setIn(new ByteArrayInputStream(translate("f^Rf^G").getBytes()));
        try {
            reader.readLine();
            fail("Expected an EndOfFileException to be thrown");
        } catch (EndOfFileException e) {
            // expected
        }
        assertEquals("f", reader.getBuffer().toString());
        assertEquals(3, history.size());
    }

    @Test
    public void testAbortingAfterSearchingPreviousLinesGivesBlank() throws Exception {
        DefaultHistory history = setupHistory();

        String readLineResult;
        in.setIn(new ByteArrayInputStream(translate("f^Rf\nfoo^G").getBytes()));
        readLineResult = reader.readLine();
        assertEquals("f", readLineResult);
        assertEquals(4, history.size());

        try {
            reader.readLine();
            fail("Expected an EndOfFileException to be thrown");
        } catch (EndOfFileException e) {
            // expected
        }
        assertEquals("", reader.getBuffer().toString());
        assertEquals(4, history.size());
    }

    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void testSearchMatchingIsProtectedAgainstReDoS() throws Exception {
        // matches() runs the incremental search pattern against the buffer and
        // every history line; a catastrophic backtracking pattern must not hang it
        // (the back reference defeats the loop memoization of JDK 9+)
        Method matches = LineReaderImpl.class.getDeclaredMethod("matches", Pattern.class, String.class, int.class);
        matches.setAccessible(true);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append('a');
        }
        String line = sb.append('!').toString();

        long start = System.nanoTime();
        List<?> evil = (List<?>) matches.invoke(reader, Pattern.compile("(a+)+\\1$"), line, 0);
        long elapsedMs = (System.nanoTime() - start) / 1000000;
        assertTrue(evil.isEmpty());
        assertTrue(elapsedMs < 10000, "Search took " + elapsedMs + " ms");

        List<?> found = (List<?>) matches.invoke(reader, Pattern.compile("a+!"), line, 0);
        assertEquals(1, found.size());
    }

    @Test
    public void testPreviousCommandSuggestionTreatsBufferLiterally() throws Exception {
        DefaultHistory history = setupHistory();
        history.add("echo (a+)+$ [x] \\Q.*");
        Method matchPreviousCommand = LineReaderImpl.class.getDeclaredMethod("matchPreviousCommand", String.class);
        matchPreviousCommand.setAccessible(true);
        assertEquals(" [x] \\Q.*", matchPreviousCommand.invoke(reader, "echo (a+)+$"));
        assertEquals(".*", matchPreviousCommand.invoke(reader, "echo (a+)+$ [x] \\Q"));
        assertEquals("", matchPreviousCommand.invoke(reader, "ech."));
        assertEquals("addle", matchPreviousCommand.invoke(reader, "f"));
    }

    @Test
    public void testSearchOnEmptyHistory() throws Exception {
        DefaultHistory history = setupHistory();
        history.purge();

        in.setIn(new ByteArrayInputStream(translate("^Sa").getBytes()));
        try {
            reader.readLine();
            fail("Expected an EndOfFileException to be thrown");
        } catch (EndOfFileException e) {
            // expected
        }
    }
}
