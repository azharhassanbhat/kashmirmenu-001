package com.kashmirmenu.app.service;

import com.kashmirmenu.app.service.MenuCsv.Problem;
import com.kashmirmenu.app.service.MenuCsv.Result;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Reading a menu from CSV: what is accepted, what is refused, and that errors point at the right line. */
class MenuCsvTest {

    private static final String HEAD = "name,category,price,veg,description,available,prep_time\n";

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static Problem only(Result r) {
        assertEquals(1, r.problems().size(), r.problems().toString());
        return r.problems().get(0);
    }

    // ------------------------------------------------------------ the happy path

    @Test
    void aSimpleFileBecomesRows() {
        Result r = MenuCsv.parse(HEAD
                + "Rogan Josh,Mains,450,non-veg,Slow cooked lamb,yes,25\n"
                + "Paneer Tikka,Starters,280.50,veg,,,\n");
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(2, r.rows().size());
        var a = r.rows().get(0);
        assertEquals("Rogan Josh", a.name());
        assertEquals("Mains", a.category());
        assertEquals(d("450.00"), a.price());
        assertFalse(a.veg());
        assertEquals("Slow cooked lamb", a.description());
        assertEquals(25, a.prepMinutes());
        assertEquals(2, a.line());
        var b = r.rows().get(1);
        assertTrue(b.veg());
        assertEquals(d("280.50"), b.price());
        assertNull(b.description());
        assertTrue(b.available());
        assertEquals(MenuCsv.DEFAULT_PREP_MINUTES, b.prepMinutes());
    }

    @Test
    void optionalColumnsMayBeLeftOutEntirely() {
        Result r = MenuCsv.parse("name,category,price,veg\nNaan,Breads,40,veg\n");
        assertTrue(r.ok(), r.problems().toString());
        assertTrue(r.rows().get(0).available());
        assertEquals(MenuCsv.DEFAULT_PREP_MINUTES, r.rows().get(0).prepMinutes());
    }

    @Test
    void columnsMayComeInAnyOrderAndExtraColumnsAreIgnored() {
        Result r = MenuCsv.parse("price,notes,VEG,Category,Name\n99,ignore me,v,Drinks,Lassi\n");
        assertTrue(r.ok(), r.problems().toString());
        var row = r.rows().get(0);
        assertEquals("Lassi", row.name());
        assertEquals("Drinks", row.category());
        assertTrue(row.veg());
    }

    @Test
    void commonHeaderSpellingsAreUnderstood() {
        Result r = MenuCsv.parse("Dish Name,Section,Rate,Veg / Non-Veg\nSamosa,Snacks,20,veg\n".replace("Veg / Non-Veg", "Is Veg"));
        assertTrue(r.ok(), r.problems().toString());
        assertEquals("Samosa", r.rows().get(0).name());
    }

    // -------------------------------------------------------- Excel and Sheets quirks

    @Test
    void aByteOrderMarkFromExcelIsIgnored() {
        Result r = MenuCsv.parse("\uFEFFname,category,price,veg\nNaan,Breads,40,veg\n");
        assertTrue(r.ok(), r.problems().toString());
    }

    @Test
    void windowsLineEndingsAndAMissingFinalNewlineWork() {
        Result r = MenuCsv.parse("name,category,price,veg\r\nNaan,Breads,40,veg\r\nTea,Drinks,15,veg");
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(2, r.rows().size());
        assertEquals(3, r.rows().get(1).line());
    }

    @Test
    void quotedFieldsMayContainCommasQuotesAndLineBreaks() {
        Result r = MenuCsv.parse(HEAD
                + "\"Biryani, Special\",Mains,350,non-veg,\"Rice, lamb and \"\"secret\"\" spices\nserved hot\",yes,30\n"
                + "Tea,Drinks,15,veg,,,\n");
        assertTrue(r.ok(), r.problems().toString());
        assertEquals("Biryani, Special", r.rows().get(0).name());
        assertEquals("Rice, lamb and \"secret\" spices served hot", r.rows().get(0).description());
        // The quoted line break pushes the next record down: Tea starts on line 4.
        assertEquals(4, r.rows().get(1).line());
    }

    @Test
    void semicolonSeparatedFilesFromSomeExcelLocalesAreRead() {
        Result r = MenuCsv.parse("name;category;price;veg\nNaan;Breads;40;veg\n");
        assertTrue(r.ok(), r.problems().toString());
        assertEquals("Naan", r.rows().get(0).name());
    }

    @Test
    void blankLinesAreSkippedWithoutShiftingLineNumbers() {
        Result r = MenuCsv.parse("name,category,price,veg\n\nNaan,Breads,40,veg\n,,,\nTea,Drinks,15,veg\n");
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(3, r.rows().get(0).line());
        assertEquals(5, r.rows().get(1).line());
    }

    @Test
    void pricesWithRupeeSignsCommasAndRsAreAccepted() {
        for (String p : new String[]{"\u20B9450", "Rs. 450", "Rs450", "INR 450", "\"4,50\"".replace("4,50", "450"), "450.00"}) {
            Result r = MenuCsv.parse("name,category,price,veg\nX,Y," + p + ",veg\n");
            assertTrue(r.ok(), p + " " + r.problems());
            assertEquals(d("450.00"), r.rows().get(0).price(), p);
        }
        Result thousands = MenuCsv.parse("name,category,price,veg\nX,Y,\"1,250.50\",veg\n");
        assertEquals(d("1250.50"), thousands.rows().get(0).price());
    }

    // ------------------------------------------------------------------ veg is never guessed

    @Test
    void theVegColumnIsRequired() {
        Problem p = only(MenuCsv.parse("name,category,price\nNaan,Breads,40\n"));
        assertEquals("veg", p.field());
        assertEquals(1, p.line());
    }

    @Test
    void aRowWithNoVegValueIsRefusedNotDefaulted() {
        Problem p = only(MenuCsv.parse("name,category,price,veg\nKebab,Starters,300,\n"));
        assertEquals("veg", p.field());
        assertEquals(2, p.line());
        assertTrue(p.message().contains("nothing is assumed"));
    }

    @Test
    void anUnrecognisedVegValueIsRefusedAndQuoted() {
        Problem p = only(MenuCsv.parse("name,category,price,veg\nKebab,Starters,300,maybe\n"));
        assertTrue(p.message().contains("maybe"));
    }

    @Test
    void manyWaysOfSayingVegAndNonVegAreUnderstood() {
        for (String v : new String[]{"veg", "Vegetarian", "V", "yes", "TRUE"}) {
            assertTrue(MenuCsv.parse("name,category,price,veg\nX,Y,1,\"" + v + "\"\n").rows().get(0).veg(), v);
        }
        for (String v : new String[]{"non-veg", "Non Veg", "nonveg", "NV", "no", "false", "egg", "meat"}) {
            assertFalse(MenuCsv.parse("name,category,price,veg\nX,Y,1,\"" + v + "\"\n").rows().get(0).veg(), v);
        }
    }

    // ------------------------------------------------------------------ refusing bad rows

    @Test
    void missingRequiredColumnsAreNamed() {
        Result r = MenuCsv.parse("title,cost,veg\nNaan,40,veg\n");
        assertEquals(2, r.problems().size());   // name and category; cost is an accepted spelling of price
        assertTrue(r.problems().stream().anyMatch(p -> p.field().equals("name")));
        assertTrue(r.problems().stream().anyMatch(p -> p.field().equals("category")));
    }

    @Test
    void aColumnThatAppearsTwiceIsRefused() {
        Problem p = only(MenuCsv.parse("name,category,price,veg,price\nNaan,Breads,40,veg,50\n"));
        assertTrue(p.message().contains("twice"));
    }

    @Test
    void badPricesAreRefusedWithTheirLine() {
        String[][] cases = {{"", "missing"}, {"abc", "not a price"}, {"0", "more than 0"}, {"-5", "not a price"},
                {"12.345", "2 decimal"}, {"100000", "cannot be more"}, {"1e3", "not a price"}};
        for (String[] c : cases) {
            Problem p = only(MenuCsv.parse("name,category,price,veg\nNaan,Breads,\"" + c[0] + "\",veg\n"));
            assertEquals("price", p.field(), c[0]);
            assertEquals(2, p.line());
            assertTrue(p.message().contains(c[1]), c[0] + " -> " + p.message());
        }
    }

    @Test
    void missingNameOrCategoryIsRefused() {
        Result r = MenuCsv.parse("name,category,price,veg\n,Breads,40,veg\nNaan,,40,veg\n");
        assertEquals(2, r.problems().size());
        assertEquals(2, r.problems().get(0).line());
        assertEquals(3, r.problems().get(1).line());
    }

    @Test
    void overLongNamesCategoriesAndDescriptionsAreRefused() {
        assertTrue(only(MenuCsv.parse("name,category,price,veg\n" + "x".repeat(151) + ",A,1,veg\n")).message().contains("150"));
        assertTrue(only(MenuCsv.parse("name,category,price,veg\nX," + "x".repeat(101) + ",1,veg\n")).message().contains("100"));
        assertTrue(only(MenuCsv.parse("name,category,price,veg,description\nX,Y,1,veg," + "x".repeat(1001) + "\n")).message().contains("1000"));
        assertTrue(MenuCsv.parse("name,category,price,veg\n" + "x".repeat(150) + ",A,1,veg\n").ok());
    }

    @Test
    void badAvailableAndPrepTimeValuesAreRefused() {
        assertEquals("available", only(MenuCsv.parse(HEAD + "X,Y,1,veg,,maybe,5\n")).field());
        assertEquals("prep_time", only(MenuCsv.parse(HEAD + "X,Y,1,veg,,yes,soon\n")).field());
        assertEquals("prep_time", only(MenuCsv.parse(HEAD + "X,Y,1,veg,,yes,301\n")).field());
        assertEquals("prep_time", only(MenuCsv.parse(HEAD + "X,Y,1,veg,,yes,-1\n")).field());
        assertTrue(MenuCsv.parse(HEAD + "X,Y,1,veg,,no,0\n").ok());
        assertTrue(MenuCsv.parse(HEAD + "X,Y,1,veg,,no,20 min\n").ok());
        assertFalse(MenuCsv.parse(HEAD + "X,Y,1,veg,,no,5\n").rows().get(0).available());
    }

    @Test
    void theSameDishTwiceInACategoryIsRefusedAndPointsAtTheFirst() {
        Result r = MenuCsv.parse("name,category,price,veg\nNaan,Breads,40,veg\nnaan,breads,45,veg\n");
        Problem p = only(r);
        assertEquals(3, p.line());
        assertTrue(p.message().contains("line 2"));
        assertEquals(1, r.rows().size(), "the first one is still a valid row");
    }

    @Test
    void theSameNameInDifferentCategoriesIsFine() {
        assertTrue(MenuCsv.parse("name,category,price,veg\nTea,Hot drinks,15,veg\nTea,Bulk orders,150,veg\n").ok());
    }

    @Test
    void everyBadRowIsReportedAtOnceNotJustTheFirst() {
        Result r = MenuCsv.parse("name,category,price,veg\n,A,1,veg\nB,,2,veg\nC,D,x,veg\nE,F,5,\n");
        assertEquals(4, r.problems().size());
        assertTrue(r.rows().isEmpty());
    }

    // -------------------------------------------------------------------- the file as a whole

    @Test
    void emptyAndHeaderOnlyFilesAreRefused() {
        assertEquals(0, only(MenuCsv.parse("")).line());
        assertEquals(0, only(MenuCsv.parse("   \n  \n")).line());
        assertEquals(0, only(MenuCsv.parse(null)).line());
        assertTrue(only(MenuCsv.parse("name,category,price,veg\n")).message().contains("no dishes"));
    }

    @Test
    void anUnclosedQuoteIsReportedWithItsLine() {
        Problem p = only(MenuCsv.parse("name,category,price,veg\nNaan,\"Breads,40,veg\n"));
        assertEquals(2, p.line());
        assertTrue(p.message().contains("never closed"));
    }

    @Test
    void aFileOverTheRowLimitIsRefused() {
        StringBuilder sb = new StringBuilder("name,category,price,veg\n");
        for (int i = 0; i <= MenuCsv.MAX_ROWS; i++) {
            sb.append("Dish ").append(i).append(",Mains,10,veg\n");
        }
        assertTrue(only(MenuCsv.parse(sb.toString())).message().contains("500"));
        StringBuilder ok = new StringBuilder("name,category,price,veg\n");
        for (int i = 0; i < MenuCsv.MAX_ROWS; i++) {
            ok.append("Dish ").append(i).append(",Mains,10,veg\n");
        }
        assertEquals(MenuCsv.MAX_ROWS, MenuCsv.parse(ok.toString()).rows().size());
    }

    @Test
    void aHugeFileIsRefusedWithoutBeingParsed() {
        assertTrue(only(MenuCsv.parse("a".repeat(MenuCsv.MAX_CHARS + 1))).message().contains("too large"));
    }

    @Test
    void controlCharactersAreStrippedAndWhitespaceCollapsed() {
        Result r = MenuCsv.parse("name,category,price,veg\n\"  Rogan\u0000   Josh \",Mains,1,veg\n");
        assertEquals("Rogan Josh", r.rows().get(0).name());
    }

    @Test
    void unicodeNamesInKashmiriUrduAndHindiSurvive() {
        Result r = MenuCsv.parse("name,category,price,veg\n\u0917\u0941\u0936\u094D\u0924\u093E\u092C\u093E,\u0916\u093E\u0928\u093E,500,non-veg\n\u06A9\u0634\u0645\u06CC\u0631\u06CC \u0642\u06C1\u0648\u06C1,\u0686\u0627\u0626\u06D2,40,veg\n");
        assertTrue(r.ok(), r.problems().toString());
        assertEquals("\u0917\u0941\u0936\u094D\u0924\u093E\u092C\u093E", r.rows().get(0).name());
    }

    @Test
    void aFormulaLookingNameIsKeptAsPlainTextNotExecuted() {
        // We never write spreadsheets, and the UI escapes text, so it is stored as typed.
        Result r = MenuCsv.parse("name,category,price,veg\n=1+1,Mains,10,veg\n");
        assertTrue(r.ok());
        assertEquals("=1+1", r.rows().get(0).name());
    }
}
