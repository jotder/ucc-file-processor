package com.gamma.acquire;

import org.junit.jupiter.api.Test;

import static com.gamma.acquire.DuplicatePolicy.Decision;
import static com.gamma.acquire.DuplicatePolicy.Mode;
import static com.gamma.acquire.DuplicatePolicy.OnChange;
import static org.junit.jupiter.api.Assertions.*;

/** The pure dup/change decision logic + the config-string enum mapping. */
class DuplicatePolicyTest {

    private static LedgerEntry prior(long size, String checksum, long mtime) {
        return new LedgerEntry("S", "f.csv", "f.csv", size, checksum, mtime, 0L, LedgerEntry.PROCESSED);
    }

    @Test
    void noPriorIsAlwaysNew() {
        assertEquals(Decision.NEW, DuplicatePolicy.decide(Mode.PATH, null, 1, 1, "x"));
        assertEquals(Decision.NEW, DuplicatePolicy.decide(Mode.CHECKSUM, null, 1, 1, "x"));
    }

    @Test
    void pathModeTreatsAnyReseenPathAsDuplicate() {
        assertEquals(Decision.DUPLICATE, DuplicatePolicy.decide(Mode.PATH, prior(100, "a", 10), 999, 999, "different"));
    }

    @Test
    void metadataComparesSizeAndMtime() {
        LedgerEntry p = prior(100, null, 10);
        assertEquals(Decision.DUPLICATE, DuplicatePolicy.decide(Mode.METADATA, p, 100, 10, null));
        assertEquals(Decision.CHANGED,   DuplicatePolicy.decide(Mode.METADATA, p, 101, 10, null));   // size grew
        assertEquals(Decision.CHANGED,   DuplicatePolicy.decide(Mode.METADATA, p, 100, 11, null));   // touched
    }

    @Test
    void checksumComparesContentHash() {
        LedgerEntry p = prior(100, "abc", 10);
        assertEquals(Decision.DUPLICATE, DuplicatePolicy.decide(Mode.CHECKSUM, p, 100, 10, "abc"));
        assertEquals(Decision.CHANGED,   DuplicatePolicy.decide(Mode.CHECKSUM, p, 100, 10, "xyz"));   // same size, new content
        assertEquals(Decision.CHANGED,   DuplicatePolicy.decide(Mode.CHECKSUM, p, 100, 10, null));    // unknown ⇒ not equal
    }

    @Test
    void onChangePolicyDrivesReprocessAndAlert() {
        assertFalse(DuplicatePolicy.reprocessOnChange(OnChange.IGNORE));
        assertTrue(DuplicatePolicy.reprocessOnChange(OnChange.REPROCESS));
        assertTrue(DuplicatePolicy.reprocessOnChange(OnChange.ALERT));
        assertTrue(DuplicatePolicy.reprocessOnChange(OnChange.ARCHIVE_OLD_VERSION));
        assertTrue(DuplicatePolicy.alertsOnChange(OnChange.ALERT));
        assertFalse(DuplicatePolicy.alertsOnChange(OnChange.REPROCESS));
    }

    @Test
    void configStringsMapToEnums() {
        assertEquals(Mode.CHECKSUM, Mode.from("checksum"));
        assertEquals(Mode.METADATA, Mode.from("METADATA"));
        assertEquals(Mode.PATH, Mode.from("anything-else"));
        assertEquals(Mode.PATH, Mode.from(null));
        assertEquals(OnChange.ARCHIVE_OLD_VERSION, OnChange.from("archive_old_version"));
        assertEquals(OnChange.IGNORE, OnChange.from("ignore"));
        assertEquals(OnChange.REPROCESS, OnChange.from(null));
    }
}
