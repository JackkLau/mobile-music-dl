import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.jar.JarFile;

/** Read every signed payload entry so JAR verification cannot succeed on an unsigned AAB. */
class VerifySignedBundle {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected bundle path and certificate SHA-256");
        }
        int verified = 0;
        var names = new HashSet<String>();
        try (var jar = new JarFile(new File(args[0]), true)) {
            var entries = jar.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!names.add(entry.getName())) throw new SecurityException("Duplicate bundle entry");
                if (entry.isDirectory()) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    while (input.read(buffer) != -1) { /* A full read triggers signature verification. */ }
                }
                String name = entry.getName().toUpperCase(java.util.Locale.ROOT);
                if (name.equals("META-INF/MANIFEST.MF") || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                var signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1) throw new SecurityException("Unsigned or multiply signed bundle payload");
                var certificate = (X509Certificate) signers[0].getSignerCertPath().getCertificates().get(0);
                certificate.checkValidity();
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
                if (!digest.equals(args[1])) throw new SecurityException("Bundle certificate mismatch");
                verified++;
            }
        }
        if (verified == 0 || !names.contains("base/manifest/AndroidManifest.xml")) {
            throw new SecurityException("Bundle has no verified base manifest");
        }
        System.out.println("AAB verified: " + verified + " signed payload entries, existing release certificate.");
    }
}
