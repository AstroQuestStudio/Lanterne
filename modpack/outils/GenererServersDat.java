import java.io.*;
import java.nio.file.*;

/** Ecrit (ou relit avec « --lire ») un servers.dat NBT non compresse : java GenererServersDat.java sortie.dat "Nom" adresse[:port] */
public class GenererServersDat {
    static void str(DataOutputStream o, String name, String value) throws IOException {
        o.writeByte(8); o.writeUTF(name); o.writeUTF(value);
    }

    public static void main(String[] a) throws Exception {
        if (a.length == 2 && a[0].equals("--lire")) { lire(Path.of(a[1])); return; }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(buf);
        o.writeByte(10); o.writeUTF("");                 // compound racine, nom vide
        o.writeByte(9); o.writeUTF("servers");           // liste « servers »
        o.writeByte(10); o.writeInt(1);                  // de 1 compound
        str(o, "ip", a[2]);
        str(o, "name", a[1]);
        o.writeByte(0);                                  // fin du compound serveur
        o.writeByte(0);                                  // fin du compound racine
        Files.write(Path.of(a[0]), buf.toByteArray());
        System.out.println("ecrit " + a[0] + " (" + buf.size() + " octets)");
        lire(Path.of(a[0]));
    }

    static void lire(Path p) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(p)))) {
            if (in.readByte() != 10) throw new IOException("racine n'est pas un compound");
            String rootName = in.readUTF();
            byte t = in.readByte();
            String listName = in.readUTF();
            if (t != 9 || !listName.equals("servers")) throw new IOException("pas de liste servers");
            byte elem = in.readByte(); int n = in.readInt();
            if (elem != 10) throw new IOException("elements non compound");
            System.out.println("VALIDE : racine=\"" + rootName + "\", liste=\"" + listName + "\", " + n + " serveur(s)");
            for (int i = 0; i < n; i++) {
                for (byte tag; (tag = in.readByte()) != 0; ) {
                    String k = in.readUTF();
                    if (tag == 8) System.out.println("  " + k + " = " + in.readUTF());
                    else throw new IOException("tag inattendu " + tag);
                }
            }
            if (in.readByte() != 0) throw new IOException("fin de racine manquante");
            if (in.available() != 0) throw new IOException("octets en trop");
        }
    }
}
