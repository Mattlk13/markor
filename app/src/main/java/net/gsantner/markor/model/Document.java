/*#######################################################
 *
 *   Maintained 2017-2025 by Gregor Santner <gsantner AT mailbox DOT org>
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.model;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;

import net.gsantner.markor.ApplicationObject;
import net.gsantner.markor.R;
import net.gsantner.markor.activity.MainActivity;
import net.gsantner.markor.format.FormatRegistry;
import net.gsantner.markor.format.markdown.MarkdownTextConverter;
import net.gsantner.markor.util.MarkorContextUtils;
import net.gsantner.opoc.util.GsContextUtils;
import net.gsantner.opoc.util.GsFileUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.Locale;

import other.de.stanetz.jpencconverter.JavaPasswordbasedCryption;

@SuppressWarnings({"WeakerAccess", "UnusedReturnValue", "unused", "UnnecessaryLocalVariable"})
public class Document implements Serializable {
    private static final int MAX_TITLE_EXTRACTION_LENGTH = 25;

    private static final String MOD_PREF_NAME = "DOCUMENT_MOD_TIMES";
    public static final String EXTRA_DOCUMENT = "EXTRA_DOCUMENT"; // Document
    public static final String EXTRA_FILE = "EXTRA_FILE"; // java.io.File
    public static final String EXTRA_FILE_LINE_NUMBER = "EXTRA_FILE_LINE_NUMBER"; // int
    public static final String EXTRA_DO_PREVIEW = "EXTRA_DO_PREVIEW";

    // Exposed properties
    public final File file;
    public final String extension;
    public final String title;
    public final String path;

    private long _modTime = -1; // The file's mod time when it was last touched by this document
    private long _touchTime = -1; // The last time this document touched the file
    private GsFileUtils.FileInfo _fileInfo;
    private @StringRes
    int _format = FormatRegistry.FORMAT_UNKNOWN;
    private transient SharedPreferences _modTimePref;

    // Used to check if string changed
    private long _lastHash = 0;
    private int _lastLength = -1;

    public Document(@NonNull final File f) {
        path = GsFileUtils.getPath(f);
        file = new File(path);
        title = GsFileUtils.getFilenameWithoutExtension(file);
        extension = GsFileUtils.getFilenameExtension(file);

        // Set initial format
        for (final FormatRegistry.Format format : FormatRegistry.FORMATS) {
            if (format.converter == null || format.converter.isFileOutOfThisFormat(file)) {
                setFormat(format.format);
                break;
            }
        }
    }

    private void initModTimePref() {
        // We do not do this in constructor as we want to init after deserialization too
        if (_modTimePref == null) {
            _modTimePref = ApplicationObject.get().getApplicationContext().getSharedPreferences(MOD_PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    private long getGlobalTouchTime() {
        initModTimePref();
        return _modTimePref.getLong(file.getAbsolutePath(), 0);
    }

    private void setGlobalTouchTime() {
        initModTimePref();
        _touchTime = System.currentTimeMillis();
        _modTimePref.edit().putLong(file.getAbsolutePath(), _touchTime).apply();
    }

    public void resetChangeTracking() {
        _modTime = _touchTime = -1;
    }

    public boolean hasFileChangedSinceLastLoad() {
        return _modTime < 0                            // Never read
                || _touchTime < 0                      // Never read
                || fileModTime() > _modTime            // File mod time updated
                || getGlobalTouchTime() > _touchTime;  // File has been modified by another document
    }

    public long fileModTime() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return Files.readAttributes(file.toPath(), BasicFileAttributes.class).lastModifiedTime().toMillis();
            }
        } catch (IOException ignored) {
        }
        return file.lastModified();
    }

    public long fileBytes() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return Files.readAttributes(file.toPath(), BasicFileAttributes.class).size();
            }
        } catch (Exception ignored) {
        }
        return file.length();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Document) {
            Document other = ((Document) obj);
            return equalsc(file, other.file)
                    && equalsc(title, other.title)
                    && (getFormat() == other.getFormat());
        }
        return super.equals(obj);
    }

    private static boolean equalsc(Object o1, Object o2) {
        return (o1 == null && o2 == null) || o1 != null && o1.equals(o2);
    }

    public String getFileExtension() {
        return extension;
    }

    @StringRes
    public int getFormat() {
        return _format;
    }

    public void setFormat(int format) {
        _format = format;
    }

    public static boolean isEncrypted(File file) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && file.getName().endsWith(JavaPasswordbasedCryption.DEFAULT_ENCRYPTION_EXTENSION);
    }

    public boolean isBinaryFileNoTextLoading() {
        return file != null && FormatRegistry.CONVERTER_EMBEDBINARY.isFileOutOfThisFormat(file);
    }

    public boolean isEncrypted() {
        return isEncrypted(file);
    }

    private void setContentHash(final CharSequence s) {
        _lastLength = s != null ? s.length() : 0;
        _lastHash = s != null ? GsFileUtils.crc32(s) : 0;
    }

    public boolean isContentSame(final CharSequence s) {
        return s != null && s.length() == _lastLength && _lastHash == GsFileUtils.crc32(s);
    }

    public synchronized @Nullable
    String loadContent(final Context context) {
        String content;
        final char[] pw;

        if (isBinaryFileNoTextLoading()) {
            content = "";
        } else if (isEncrypted() && (pw = getPasswordWithWarning(context)) != null) {
            try {
                final byte[] encryptedContext = GsFileUtils.readCloseStreamWithSize(new FileInputStream(file), (int) file.length());
                if (encryptedContext.length > JavaPasswordbasedCryption.Version.NAME_LENGTH) {
                    content = JavaPasswordbasedCryption.getDecryptedText(encryptedContext, pw);
                } else {
                    content = new String(encryptedContext, StandardCharsets.UTF_8);
                }
            } catch (FileNotFoundException e) {
                Log.e(Document.class.getName(), "loadDocument:  File " + file + " not found.");
                content = "";
            } catch (JavaPasswordbasedCryption.EncryptionFailedException |
                     IllegalArgumentException e) {
                Toast.makeText(context, R.string.could_not_decrypt_file_content_wrong_password_or_is_the_file_maybe_not_encrypted, Toast.LENGTH_LONG).show();
                Log.e(Document.class.getName(), "loadDocument:  decrypt failed for File " + file + ". " + e.getMessage(), e);
                content = "";
            }
        } else {
            // We try to load 2x. If both times fail, we return null
            Pair<String, GsFileUtils.FileInfo> result = GsFileUtils.readTextFileFast(file);
            if (result.second.ioError) {
                Log.i(Document.class.getName(), "loadDocument:  File " + file + " read error, trying again.");
                result = GsFileUtils.readTextFileFast(file);
            }
            content = result.first;
            _fileInfo = result.second;
        }

        if (MainActivity.IS_DEBUG_ENABLED) {
            AppSettings.appendDebugLog(
                    "\n\n\n--------------\nLoaded document, filepattern "
                            + title.replaceAll(".*\\.", "-")
                            + ", chars: " + content.length() + " bytes:" + content.getBytes().length
                            + "(" + GsFileUtils.getReadableFileSize(content.getBytes().length, true) +
                            "). Language >" + Locale.getDefault()
                            + "<, Language override >" + AppSettings.get(context).getLanguage() + "<");
        }

        if (_fileInfo != null && _fileInfo.ioError) {
            // Force next load on failure
            setContentHash(null);
            resetChangeTracking();
            Log.i(Document.class.getName(), "loadDocument:  File " + file + " read error, could not load file.");
            return null;
        } else {
            // Also set hash and time on load - should prevent unnecessary saves
            setContentHash(content);
            _modTime = fileModTime();
            setGlobalTouchTime();
            return content;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private static char[] getPasswordWithWarning(final Context context) {
        final char[] pw = AppSettings.get(context).getDefaultPassword();
        if (pw == null || pw.length == 0) {
            final String warningText = context.getString(R.string.no_password_set_cannot_encrypt_decrypt);
            Toast.makeText(context, warningText, Toast.LENGTH_LONG).show();
            Log.w(Document.class.getName(), warningText);
            return null;
        }
        return pw;
    }

    public boolean testCreateParent() {
        return testCreateParent(file);
    }

    public static boolean testCreateParent(final File file) {
        try {
            final File parent = file.getParentFile();
            return parent != null && (parent.exists() || parent.mkdirs());
        } catch (NullPointerException e) {
            return false;
        }
    }

    public boolean saveContent(final Activity context, final CharSequence content) {
        return saveContent(context, content, null, false);
    }

    @SuppressWarnings("ConstantConditions")
    public synchronized boolean saveContent(final Activity context, final CharSequence content, MarkorContextUtils cu, final boolean isManualSave) {
        if (isBinaryFileNoTextLoading()) {
            return true;
        }

        if (!isManualSave && TextUtils.getTrimmedLength(content) < GsContextUtils.TEXTFILE_OVERWRITE_MIN_TEXT_LENGTH) {
            return false;
        }

        if (!testCreateParent()) {
            return false;
        }

        // Don't write same content if base file not changed
        if (!hasFileChangedSinceLastLoad() && isContentSame(content)) {
            return true;
        }

        boolean success;
        try {
            final char[] pw;
            final byte[] contentAsBytes;
            if (isEncrypted() && (pw = getPasswordWithWarning(context)) != null) {
                contentAsBytes = new JavaPasswordbasedCryption(Build.VERSION.SDK_INT, new SecureRandom()).encrypt(content.toString(), pw);
            } else {
                contentAsBytes = content.toString().getBytes();
            }

            cu = cu != null ? cu : new MarkorContextUtils(context);
            final boolean isContentResolverProxyFile = cu.isContentResolverProxyFile(file);
            if (cu.isUnderStorageAccessFolder(context, file, false) || isContentResolverProxyFile) {
                cu.writeFile(context, file, false, (fileOpened, fos) -> {
                    try {
                        if (_fileInfo != null && _fileInfo.hasBom) {
                            fos.write(0xEF);
                            fos.write(0xBB);
                            fos.write(0xBF);
                        }
                        fos.write(contentAsBytes);

                        // Also overwrite content resolver proxy file in addition to writing back to the origin
                        if (isContentResolverProxyFile) {
                            GsFileUtils.writeFile(file, contentAsBytes, _fileInfo);
                        }

                    } catch (Exception e) {
                        Log.i(Document.class.toString(), e.getMessage());
                    }
                });
                success = true;
            } else {
                // Try write 2x
                success = GsFileUtils.writeFile(file, contentAsBytes, _fileInfo);
                if (!success || fileBytes() < contentAsBytes.length) {
                    success = GsFileUtils.writeFile(file, contentAsBytes, _fileInfo);
                }
            }

            final long size = fileBytes();
            if (fileBytes() < contentAsBytes.length) {
                success = false;
                Log.i(Document.class.getName(), "File write failed; size = " + size + "; length = " + contentAsBytes.length + "; file=" + file);
            }

        } catch (JavaPasswordbasedCryption.EncryptionFailedException e) {
            Log.e(Document.class.getName(), "writeContent:  encrypt failed for File " + path + ". " + e.getMessage(), e);
            Toast.makeText(context, R.string.could_not_encrypt_file_content_the_file_was_not_saved, Toast.LENGTH_LONG).show();
            success = false;
        }

        if (success) {
            setContentHash(content);
            _modTime = fileModTime();
            setGlobalTouchTime();
        } else {
            Log.i(Document.class.getName(), "File write failed, size = " + fileBytes() + "; file=" + file);
        }

        return success;
    }

    public static String getMaskedContent(final String text) {
        final String httpToken = "§$§$§$§$";
        return text
                .replace("http://", httpToken)
                .replace("https://", httpToken)
                .replaceAll("\\w", "a")
                .replace(httpToken, "https://");
    }

    public static String normalizeFilename(final String name) {
        if (TextUtils.isEmpty(name.trim())) {
            return getFileNameWithTimestamp(false);
        } else {
            return GsFileUtils.getFilteredFilenameWithoutDisallowedChars(name);
        }
    }

    public static String filenameFromContent(final String content) {
        if (!TextUtils.isEmpty(content)) {
            final String contentL1 = content.split("\n")[0];
            if (contentL1.length() < MAX_TITLE_EXTRACTION_LENGTH) {
                return contentL1;
            } else {
                return contentL1.substring(0, MAX_TITLE_EXTRACTION_LENGTH);
            }
        } else {
            return getFileNameWithTimestamp(false);
        }
    }

    // Convenient wrapper
    private static String getFileNameWithTimestamp(final boolean includeExt) {
        final String ext = includeExt ? MarkdownTextConverter.EXT_MARKDOWN__TXT : "";
        return GsFileUtils.getFilenameWithTimestamp("", "", ext);
    }
}
