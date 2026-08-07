/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package ru.taximaxim.codekeeper.ui.dialogs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.api.ComparisonDepth;

import ru.taximaxim.codekeeper.ui.dialogs.CommitDialog.Warning;
import ru.taximaxim.codekeeper.ui.localizations.Messages;

/**
 * The red label of the project update dialog reports an incomplete dependency
 * set, but only a genuine validation failure may disable the OK button.
 */
class CommitDialogWarningTest {

    @Test
    void incompleteExpansionIsReportedWithoutBlocking() {
        Warning warning = CommitDialog.resolveWarning(false, false, true, false);

        assertNotNull(warning, "an incomplete dependency set must be reported");
        assertSame(Messages.CommitDialog_incomplete_depcy_expansion, warning.message());
        assertFalse(warning.blocking(), "the project update stays available");
    }

    @Test
    void completeExpansionReportsNothing() {
        assertNull(CommitDialog.resolveWarning(false, false, false, false));
    }

    @Test
    void blockingWarningsWinOverTheReportedGap() {
        Warning unchecked = CommitDialog.resolveWarning(true, false, true, true);
        assertSame(Messages.CommitDialog_unchecked_objects_can_occur_unexpected_errors, unchecked.message());
        assertTrue(unchecked.blocking());

        Warning overrides = CommitDialog.resolveWarning(false, true, true, true);
        assertSame(Messages.CommitDialog_privileges_must_be_saved, overrides.message());
        assertTrue(overrides.blocking());
    }

    @Test
    void reportedMessageIsLocalized() {
        String message = Messages.CommitDialog_incomplete_depcy_expansion;

        assertNotNull(message);
        assertFalse(message.startsWith("NLS missing message"), //$NON-NLS-1$
                "the message must exist in the localization bundles: " + message);
    }

    /**
     * A dependency set collected from a structural comparison is short, not
     * empty: the edges readable off the model alone - parent, foreign key to
     * unique, the additional dependencies of the project - are resolved
     * without any analysis, and the dialog writes every one of them into the
     * project. So the dialog may not sit silent over such a set, and may not
     * refuse it either: it says the set is short and leaves the checkboxes
     * alone.
     */
    @Test
    void aStructuralDependencySetIsReportedWithoutBlocking() {
        Warning warning = CommitDialog.resolveWarning(false, false, false, true);

        assertNotNull(warning, "a dependency set collected without analysis must be reported");
        assertSame(Messages.CommitDialog_depcy_set_incomplete_in_receive_only, warning.message());
        assertFalse(warning.blocking(), "the project update stays available");
    }

    /**
     * Both reported gaps are about the same thing - edges the loaded model does
     * not carry - and only one label exists to say it in. The structural one
     * wins because it is the wider: an unanalyzed routine body costs the edges
     * of one body, a comparison loaded without analysis costs every body and
     * every expression at once.
     */
    @Test
    void theStructuralGapIsSaidOverTheNarrowerOne() {
        Warning warning = CommitDialog.resolveWarning(false, false, true, true);

        assertSame(Messages.CommitDialog_depcy_set_incomplete_in_receive_only, warning.message());
    }

    /**
     * The depth predicate the dialog asks, and the only reason it asks it:
     * a structural comparison carries an incomplete dependency set, a full one
     * carries the whole of it.
     */
    @Test
    void onlyAFullComparisonCarriesTheWholeDependencySet() {
        assertFalse(CommitDialog.isDependencySetComplete(ComparisonDepth.STRUCTURAL_ONLY));
        assertTrue(CommitDialog.isDependencySetComplete(ComparisonDepth.FULL));
    }

    @Test
    void theStructuralMessageIsLocalized() {
        String message = Messages.CommitDialog_depcy_set_incomplete_in_receive_only;

        assertNotNull(message);
        assertFalse(message.startsWith("NLS missing message"), //$NON-NLS-1$
                "the message must exist in the localization bundles: " + message);
    }
}
