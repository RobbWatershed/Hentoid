package me.devsaki.hentoid.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.Date;
import java.util.Map;

import javax.annotation.Nonnull;

import me.devsaki.hentoid.enums.AttributeType;
import timber.log.Timber;

/**
 * Created by avluis on 06/05/2016.
 * JSON related utility class
 */
public class JsonHelper {

    private JsonHelper() {
        throw new IllegalStateException("Utility class");
    }

    public static final String JSON_MIME_TYPE = "application/json";

    public static final Type MAP_STRINGS = Types.newParameterizedType(Map.class, String.class, String.class);

    private static final Moshi MOSHI = new Moshi.Builder()
            .add(Date.class, new Rfc3339DateJsonAdapter())
            .add(new AttributeType.AttributeTypeAdapter())
            .build();


    /**
     * Serialize the given object to JSON format
     *
     * @param o    Object to serialize
     * @param type Type of the output JSON structure to use
     * @param <K>  Type of the given object
     * @return String containing the given object serialized to JSON format
     */
    public static <K> String serializeToJson(K o, Type type) {
        JsonAdapter<K> jsonAdapter = MOSHI.adapter(type);

        return jsonAdapter.toJson(o);
    }

    /**
     * Serialize and save the object contents to a json file in the given directory.
     * The JSON file is created if it doesn't exist
     *
     * @param context Context to be used
     * @param object  Object to be serialized and saved
     * @param type    Type of the output JSON structure to use
     * @param dir     Existing folder to save the JSON file to
     * @param <K>     Type of the given object
     * @return DocumentFile where the object has been serialized and saved
     * @throws IOException If anything happens during file I/O
     */
    public static <K> DocumentFile jsonToFile(@NonNull final Context context, K object, Type type, @NonNull DocumentFile dir) throws IOException {
        return jsonToFile(context, object, type, dir, Consts.JSON_FILE_NAME_V2);
    }

    /**
     * Serialize and save the object contents to a json file in the given directory.
     * The JSON file is created if it doesn't exist
     *
     * @param context  Context to be used
     * @param object   Object to be serialized and saved
     * @param type     Type of the output JSON structure to use
     * @param dir      Existing folder to save the JSON file to
     * @param fileName Name of the output file
     * @param <K>      Type of the given object
     * @return DocumentFile where the object has been serialized and saved
     * @throws IOException If anything happens during file I/O
     */
    public static <K> DocumentFile jsonToFile(@NonNull final Context context, K object, Type type, @NonNull DocumentFile dir, @NonNull final String fileName) throws IOException {
        DocumentFile file = FileHelper.findOrCreateDocumentFile(context, dir, JSON_MIME_TYPE, fileName);
        if (null == file)
            throw new IOException("Failed creating file " + fileName + " in " + dir.getUri().getPath());

        try (OutputStream output = FileHelper.getOutputStream(context, file)) {
            if (output != null) updateJson(object, type, output);
            else Timber.w("JSON file creation failed for %s", file.getUri().getPath());
        }
        return file;
    }

    /**
     * Serialize and save the object contents to the given existing file using the JSON format
     *
     * @param context Context to be used
     * @param object  Object to serialize
     * @param type    Type of the output JSON structure to use
     * @param file    File to write to
     * @param <K>     Type of the given object
     * @throws IOException If anything happens during file I/O
     */
    static <K> void updateJson(@NonNull final Context context, K object, Type type, @Nonnull DocumentFile file) throws IOException {
        if (!file.exists()) return;

        try (OutputStream output = FileHelper.getOutputStream(context, file)) {
            if (output != null) updateJson(object, type, output);
            else Timber.w("JSON file creation failed for %s", file.getUri());
        } catch (FileNotFoundException e) {
            Timber.e(e);
        }
    }

    /**
     * Serialize and save the object contents to the given OutputStream using the JSON format
     *
     * @param object Object to serialize
     * @param type   Type of the output JSON structure to use
     * @param output OutputStream to write to
     * @param <K>    Type of the given object
     * @throws IOException If anything happens during file I/O
     */
    static <K> void updateJson(K object, Type type, @Nonnull OutputStream output) throws IOException {
        byte[] bytes = serializeToJson(object, type).getBytes();
        output.write(bytes);
        FileHelper.sync(output);
        output.flush();
    }

    /**
     * Convert the JSON data contained in the given file to an object of the given type
     *
     * @param context Context to be used
     * @param f       File to read JSON data from
     * @param type    Class of the input JSON structure to use
     * @param <T>     Type of the converted object
     * @return Object of the given type representing the JSON data contained in the given file
     * @throws IOException If anything happens during file I/O
     */
    public static <T> T jsonToObject(@NonNull final Context context, DocumentFile f, Class<T> type) throws IOException {
        return jsonToObject(FileHelper.readFileAsString(context, f), type);
    }

    public static <T> T jsonToObject(File f, Class<T> type) throws IOException {
        return jsonToObject(readJsonString(f), type);
    }

    /**
     * Convert the JSON data contained in the given file to an object of the given type
     *
     * @param context Context to be used
     * @param f       File to read JSON data from
     * @param type    Type of the input JSON structure to use
     * @param <T>     Type of the converted object
     * @return Object of the given type representing the JSON data contained in the given file
     * @throws IOException If anything happens during file I/O
     */
    public static <T> T jsonToObject(@NonNull final Context context, @NonNull DocumentFile f, Type type) throws IOException {
        return jsonToObject(FileHelper.readFileAsString(context, f), type);
    }

    public static <T> T jsonToObject(File f, Class<T> type) throws IOException {
        return jsonToObject(readJsonString(f), type);
    }

    private static String readJsonString(@NonNull File f) {
        StringBuilder json = new StringBuilder();
        String sCurrentLine;
        boolean isFirst = true;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(FileHelper.getInputStream(f)))) {
            while ((sCurrentLine = br.readLine()) != null) {
                if (isFirst) {
                    // Strip UTF-8 BOMs if any
                    if (sCurrentLine.charAt(0) == '\uFEFF')
                        sCurrentLine = sCurrentLine.substring(1);
                    isFirst = false;
                }
                json.append(sCurrentLine);
            }
        } catch (IOException | IllegalArgumentException e) {
            Timber.e(e, "Error while reading %s", f.getAbsolutePath());
        }
        return json.toString();
    }

    private static String readJsonString(@NonNull final Context context, @NonNull DocumentFile f) {
        StringBuilder json = new StringBuilder();
        String sCurrentLine;
        boolean isFirst = true;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(FileHelper.getInputStream(context, f)))) {
            while ((sCurrentLine = br.readLine()) != null) {
                if (isFirst) {
                    // Strip UTF-8 BOMs if any
                    if (sCurrentLine.charAt(0) == '\uFEFF')
                        sCurrentLine = sCurrentLine.substring(1);
                    isFirst = false;
                }
                json.append(sCurrentLine);
            }
        } catch (IOException | IllegalArgumentException e) {
            Timber.e(e, "Error while reading %s", f.getUri().toString());
        }
        return json.toString();
    }

    /**
     * Convert JSON data contained in the given string to an object of the given type
     *
     * @param s    JSON data in string format
     * @param type Class of the input JSON structure to use
     * @param <T>  Type of the converted object
     * @return Object of the given type representing the JSON data contained in the given string
     * @throws IOException If anything happens during file I/O
     */
    public static <T> T jsonToObject(String s, Class<T> type) throws IOException {
        JsonAdapter<T> jsonAdapter = MOSHI.adapter(type);

        return jsonAdapter.lenient().fromJson(s);
    }

    /**
     * Convert JSON data contained in the given string to an object of the given type
     *
     * @param s    JSON data in string format
     * @param type Type of the input JSON structure to use
     * @param <T>  Type of the converted object
     * @return Object of the given type representing the JSON data contained in the given string
     * @throws IOException If anything happens during file I/O
     */
    public static <T> T jsonToObject(String s, Type type) throws IOException {
        JsonAdapter<T> jsonAdapter = MOSHI.adapter(type);

        return jsonAdapter.lenient().fromJson(s);
    }
}
