package org.kontalk.xmpp.message;

import java.util.List;

import org.kontalk.xmpp.R;

import android.content.Context;

public class VCardMessage extends PlainTextMessage {

	// actually the second one is not standard...
    public static final String[] MIME_TYPES = { "text/x-vcard", "text/vcard" };

    public VCardMessage(Context context) {
        super(context);
    }

    public VCardMessage(Context context, String id, long timestamp, String sender, byte[] content, boolean encrypted) {
        super(context, id, timestamp, sender, content, encrypted);
        // force mime type
        //mime = MIME_TYPES[0];
    }

    public VCardMessage(Context context, String id, long timestamp, String sender, byte[] content, boolean encrypted, List<String> group) {
        super(context, id, timestamp, sender, content, encrypted, group);
        // force mime type
        //mime = MIME_TYPES[0];
    }

    @Override
    public String getTextContent() {
        String s = "vCard: " + "ciao";
        if (encrypted)
            s += " " + mContext.getResources().getString(R.string.text_encrypted);
        return s;
    }

    public static String buildMediaFilename(String id, String mime) {
        return "vcard" + id.substring(id.length() - 5) + ".vcf";
    }

    public static boolean supportsMimeType(String mime) {
        for (int i = 0; i < MIME_TYPES.length; i++)
            if (MIME_TYPES[i].equalsIgnoreCase(mime))
                return true;

        return false;
    }

    @Override
    public void recycle() {
        // nothing
    }

}