package br.edu.utfpr.sd.ep1;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** PBKDF2 e salt individual, usando apenas recursos do JDK. */
final class Senhas {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERACOES = 120_000;

    private Senhas() { }

    static void definir(Banco.Usuario usuario, String senha) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        usuario.senhaSalt = Base64.getEncoder().encodeToString(salt);
        usuario.senhaHash = Base64.getEncoder().encodeToString(calcular(senha, salt));
    }

    static boolean conferir(Banco.Usuario usuario, String senha) {
        byte[] salt = Base64.getDecoder().decode(usuario.senhaSalt);
        byte[] esperado = Base64.getDecoder().decode(usuario.senhaHash);
        return MessageDigest.isEqual(esperado, calcular(senha, salt));
    }

    private static byte[] calcular(String senha, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(senha.toCharArray(), salt, ITERACOES, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 indisponivel no JDK", e);
        } finally {
            spec.clearPassword();
        }
    }
}
