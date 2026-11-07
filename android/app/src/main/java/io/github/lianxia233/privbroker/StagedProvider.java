package io.github.lianxia233.privbroker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读地暴露 StagedFiles 复制出来的安装包。
 *
 * 自家 URI 不依赖调用方的临时授权，安装全程可读；同时通过
 * grantUriPermissions 让「静默失败 → 系统安装器」的降级路径也能安全地
 * 把同一个文件交给系统安装器，避免暴露 file:// 触发 FileUriExposedException。
 */
public final class StagedProvider extends ContentProvider {
    private static final String MIME = "application/vnd.android.package-archive";

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) {
        String name = uri == null ? null : uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,120}") || name.contains("..")) {
            return null;
        }
        if (getContext() == null) {
            return null;
        }
        File f = new File(StagedFiles.srcDir(getContext()), name);
        return f.isFile() ? f : null;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.equals("r")) {
            throw new FileNotFoundException("只读");
        }
        File f = fileFor(uri);
        if (f == null) {
            throw new FileNotFoundException("安装包不存在: " + uri);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        File f = fileFor(uri);
        if (f != null) {
            c.addRow(new Object[]{f.getName(), f.length()});
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File f = fileFor(uri);
        return f != null && f.delete() ? 1 : 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
