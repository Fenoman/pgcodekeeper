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
package ru.taximaxim.codekeeper.ui.editors;

import java.util.Objects;
import java.util.Optional;

import ru.taximaxim.codekeeper.ui.Log;

/**
 * Exactly-once ownership handoff from a background comparison to its queued UI
 * callback. Closing a dropped handoff releases the resource; claiming it
 * transfers that responsibility to the callback.
 */
final class UiPublicationHandoff<T extends AutoCloseable>
        implements AutoCloseable {

    private T resource;

    UiPublicationHandoff(T resource) {
        this.resource = Objects.requireNonNull(
                resource, "resource"); //$NON-NLS-1$
    }

    synchronized Optional<T> claim() {
        T claimed = resource;
        resource = null;
        return Optional.ofNullable(claimed);
    }

    @Override
    public void close() {
        T close;
        synchronized (this) {
            close = resource;
            resource = null;
        }
        if (close != null) {
            try {
                close.close();
            } catch (Exception ex) {
                Log.log(ex);
            }
        }
    }
}
