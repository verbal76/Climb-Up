package com.hotatticgames.climbup;

import static org.junit.Assert.*;

import com.hotatticgames.climbup.ui.PixelFont;
import com.hotatticgames.climbup.ui.TextWrap;
import java.util.List;
import org.junit.Test;

public class TextWrapTest {
    private static float width(String s, float px) { return s.length() * PixelFont.ADV * px - px; }      // PixelFont.width, without a GL context

    @Test public void noLineIsWiderThanTheBoxAndNoWordIsLostAtAnyTextSize() {
        String text = "THIS UPDATE CHANGED HOW A CLIMB IS STORED, SO YOUR UNFINISHED CLIMB COULD NOT CARRY OVER. IT IS KEPT AS A LEGACY RUN. YOUR RECORDS AND SETTINGS ARE SAFE.";
        for (float px : new float[]{2f, 3.2f, 3.2f * 1.3f, 3.2f * 1.6f, 5.5f}) for (float box : new float[]{300f, 500f, 820f}) {
            List<String> lines = TextWrap.wrap(text, s -> width(s, px), box);
            StringBuilder joined = new StringBuilder();
            for (String l : lines) { assertTrue("line too wide at px " + px + " box " + box + ": " + l, width(l, px) <= box + 0.01f); joined.append(l).append(' '); }
            assertEquals("same letters in the same order (an over-wide word may be broken)", text.replace(" ", ""), joined.toString().replace(" ", ""));
        }
    }

    @Test public void aWordWiderThanTheBoxIsBrokenInsteadOfOverflowing() {
        List<String> lines = TextWrap.wrap("SHORT SUPERCALIFRAGILISTICEXPIALIDOCIOUS END", s -> width(s, 4f), 200f);
        for (String l : lines) assertTrue(l, width(l, 4f) <= 200.01f);
        assertEquals("SHORTSUPERCALIFRAGILISTICEXPIALIDOCIOUSEND", String.join("", lines).replace(" ", ""));
    }

    @Test public void emptyAndBlankTextGiveNoLines() { assertTrue(TextWrap.wrap("   ", s -> s.length(), 10f).isEmpty()); }
}
