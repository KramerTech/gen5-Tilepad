// Run locally or in CI: java tools/sign_update.java dist/tilepad.zip
// Writes <file>.sig, a SHA256withRSA signature made with the APK signing key, which Tweaks ›
// Package Manager's Check for Updates requires before it installs a download (SelfUpdate.java).
// The keystore, alias and passwords come from the same variables (and defaults) as tools/build_apk.sh,
// and the same certificate pin applies: tools/signing-cert.sha256, or $TILEPAD_CERT_PIN_FILE.
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.HexFormat;

public class sign_update {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: java tools/sign_update.java <file>");
        Path file = Path.of(args[0]), sig = Path.of(args[0] + ".sig");
        String keystore = env("TILEPAD_KEYSTORE", "keystore/tilepad.jks");
        String store = System.getenv("TILEPAD_STORE_PASSWORD");
        if (store == null || store.isEmpty()) store = Files.readString(Path.of("keystore/password")).trim();
        String key = env("TILEPAD_KEY_PASSWORD", store), alias = env("TILEPAD_KEY_ALIAS", "tilepad");

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream in = new FileInputStream(keystore)) { ks.load(in, store.toCharArray()); }
        PrivateKey privateKey = (PrivateKey) ks.getKey(alias, key.toCharArray());
        Certificate cert = ks.getCertificate(alias);
        if (privateKey == null || cert == null) throw new IllegalStateException("No key " + alias + " in " + keystore);
        Path pin = Path.of(env("TILEPAD_CERT_PIN_FILE", "tools/signing-cert.sha256"));
        if (Files.isRegularFile(pin)) {
            String want = Files.readString(pin).replaceAll("\\s", "").toLowerCase();
            String have = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
            if (!have.equals(want)) throw new IllegalStateException("Signed with the wrong key: expected certificate SHA-256 " + want + " (" + pin + ")");
        }

        byte[] data = Files.readAllBytes(file);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(privateKey); s.update(data);
        byte[] signature = s.sign();
        // Check it the way the head unit will, with the certificate's public key.
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(cert.getPublicKey()); v.update(data);
        if (!v.verify(signature)) throw new IllegalStateException("The new signature doesn't verify");
        Files.write(sig, signature);
        System.out.println(sig);
    }
    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
