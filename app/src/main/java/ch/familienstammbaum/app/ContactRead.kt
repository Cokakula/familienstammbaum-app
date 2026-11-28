package ch.familienstammbaum.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Einen im Telefonbuch gewählten Kontakt auslesen: nur diesen einen, nur auf Wunsch der Person.
 * Ergebnis: {name, given, family, tels[], mails[], addrs[{street, postcode, city, country, formatted}], bday}
 */
object ContactRead {
    private val NAME = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
    private val PHONE = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
    private val MAIL = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE
    private val POSTAL = ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE
    private val EVENT = ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE

    fun read(c: Context, uri: Uri): JSONObject? {
        val cr = c.contentResolver
        var cid: String? = null
        var display = ""
        cr.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cid = cur.getString(0)
                display = cur.getString(1) ?: ""
            }
        }
        val id = cid ?: return null
        val o = JSONObject().put("name", display)
        val tels = JSONArray()
        val mails = JSONArray()
        val addrs = JSONArray()
        val cols = arrayOf(
            ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4, ContactsContract.Data.DATA7, ContactsContract.Data.DATA9, ContactsContract.Data.DATA10
        )
        cr.query(ContactsContract.Data.CONTENT_URI, cols, "${ContactsContract.Data.CONTACT_ID} = ?", arrayOf(id), null)?.use { cur ->
            while (cur.moveToNext()) {
                val mt = cur.getString(0) ?: continue
                val d1 = cur.getString(1) ?: ""
                when (mt) {
                    NAME -> {
                        val given = cur.getString(2) ?: ""
                        val family = cur.getString(3) ?: ""
                        if (given.isNotBlank()) o.put("given", given)
                        if (family.isNotBlank()) o.put("family", family)
                    }
                    PHONE -> if (d1.isNotBlank()) tels.put(d1)
                    MAIL -> if (d1.isNotBlank()) mails.put(d1)
                    POSTAL -> addrs.put(
                        JSONObject().put("formatted", d1).put("street", cur.getString(4) ?: "").put("city", cur.getString(5) ?: "")
                            .put("postcode", cur.getString(6) ?: "").put("country", cur.getString(7) ?: "")
                    )
                    EVENT -> if (cur.getString(2) == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString() && d1.isNotBlank()) o.put("bday", d1)
                }
            }
        }
        return o.put("tels", tels).put("mails", mails).put("addrs", addrs)
    }
}
