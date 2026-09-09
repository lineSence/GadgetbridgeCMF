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
package nodomain.freeyourgadget.gadgetbridge.service.devices.cmfwatchpro.recorder;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a finished recording into the folder the user picked with the system folder chooser.
 *
 * <p>Recording always happens into app storage first: the WAV writer needs to seek back and
 * patch the header, and MediaRecorder wants a plain file path. Only the finished file is copied
 * to the destination tree, which also means a failed export never costs the recording.</p>
 *
 * <p>Uses {@link DocumentsContract} directly so no extra dependency is needed.</p>
 */
public final class CmfRecorderExporter {
    private static final Logger LOG = LoggerFactory.getLogger(CmfRecorderExporter.class);

    private static final int BUFFER_SIZE = 64 * 1024;

    private CmfRecorderExporter() {
        // utility class
    }

    /**
     * Copies {@code source} into the SAF tree behind {@code treeUriString}.
     *
     * @return a human readable description of the destination, or {@code null} when nothing
     *         was exported (no folder configured or the copy failed).
     */
    public static String export(final Context context,
                               final File source,
                               final String mimeType,
                               final String treeUriString) {
        if (source == null || !source.exists() || treeUriString == null) {
            return null;
        }

        final ContentResolver resolver = context.getContentResolver();

        InputStream in = null;
        OutputStream out = null;
        try {
            final Uri treeUri = Uri.parse(treeUriString);
            final Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
            );

            final Uri target = DocumentsContract.createDocument(
                    resolver,
                    parent,
                    mimeType,
                    source.getName()
            );
            if (target == null) {
                LOG.warn("Could not create {} in the output folder", source.getName());
                return null;
            }

            out = resolver.openOutputStream(target);
            if (out == null) {
                LOG.warn("Could not open the exported document for writing");
                return null;
            }

            in = new FileInputStream(source);
            final byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();

            LOG.info("Exported {} to {}", source.getName(), target);
            return describe(target);
        } catch (final IOException e) {
            LOG.warn("Could not export the recording", e);
            return null;
        } catch (final RuntimeException e) {
            // The persisted permission may have been revoked, or the tree no longer exists.
            LOG.warn("The output folder is not usable", e);
            return null;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** Best effort readable form of a document uri, for display in the UI. */
    public static String describe(final Uri uri) {
        if (uri == null) {
            return "";
        }

        try {
            final String decoded = Uri.decode(uri.toString());
            final int marker = decoded.lastIndexOf("/document/");
            if (marker >= 0) {
                return decoded.substring(marker + "/document/".length());
            }
            final int treeMarker = decoded.lastIndexOf("/tree/");
            if (treeMarker >= 0) {
                return decoded.substring(treeMarker + "/tree/".length());
            }
            return decoded;
        } catch (final RuntimeException e) {
            return uri.toString();
        }
    }

    public static String describe(final String uriString) {
        return uriString == null ? "" : describe(Uri.parse(uriString));
    }

    private static void closeQuietly(final java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (final IOException ignored) {
            // nothing we can do
        }
    }
}
