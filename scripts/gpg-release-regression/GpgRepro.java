import takagi.ru.monica.utils.GpgKeyGenerator;

/** Uses the compiled production generator; synthetic identities and keys only. */
public class GpgRepro {
    public static void main(String[] args) {
        String[] names = {"ho", "\u4f60\u597d"};
        for (int i = 0; i < names.length; i++) {
            for (String pass : new String[]{"", "test-only"}) {
                verify(names[i], 3072, pass);
                System.out.println("PASS RSA3072 identity=" + i + " protected=" + !pass.isEmpty());
            }
        }
        verify("RSA4096 fixture", 4096, "test-only");
        System.out.println("PASS RSA4096 protected=true");
    }

    private static void verify(String name, int bits, String pass) {
        char[] chars = pass.toCharArray();
        try {
            var key = GpgKeyGenerator.INSTANCE.generate(name, "", bits, 365, chars);
            if (!key.equals(GpgKeyGenerator.INSTANCE.parse(key.getPrivateKey()))) {
                throw new AssertionError("Private key round-trip failed");
            }
            var publicOnly = GpgKeyGenerator.INSTANCE.parse(key.getPublicKey());
            if (!key.getFingerprint().equals(publicOnly.getFingerprint())
                    || !publicOnly.getPrivateKey().isEmpty()) {
                throw new AssertionError("Public-only import failed");
            }
        } finally {
            java.util.Arrays.fill(chars, '\0');
        }
    }
}
