/*  Copyright (C) 2026 GadgetbridgeCMF contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.  */
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.watchface;

import androidx.core.content.FileProvider;

/**
 * File provider for built watchfaces.
 *
 * <p>The class adds no behaviour. It exists only because the manifest merger identifies providers
 * by class name: Gadgetbridge already declares {@link FileProvider} itself for screenshots, so a
 * second declaration of the same class fails the build with a conflict on the authority and on the
 * paths resource. A subclass gives this provider its own identity, its own authority and its own
 * paths, and leaves the existing provider untouched.</p>
 */
public class CmfWatchfaceFileProvider extends FileProvider {
}
