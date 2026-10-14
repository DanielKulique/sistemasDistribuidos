package br.edu.utfpr.sd.ep1;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Um unico leitor para IP, porta, menu e formularios. */
final class EntradaConsole {
    private final BufferedReader leitor = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    String perguntar(String mensagem) throws IOException {
        System.out.print(mensagem);
        System.out.flush();
        String linha = leitor.readLine();
        if (linha == null) throw new EOFException("Entrada de console encerrada");
        return linha;
    }

    int porta(String mensagem) throws IOException {
        while (true) {
            try { return validarPorta(perguntar(mensagem).trim()); }
            catch (IllegalArgumentException e) { System.out.println(e.getMessage()); }
        }
    }

    static int validarPorta(String texto) {
        try {
            int porta = Integer.parseInt(texto);
            if (porta >= 1 && porta <= 65535) return porta;
        } catch (NumberFormatException e) {
            // Mesma mensagem para numeros fora do intervalo e texto invalido.
        }
        throw new IllegalArgumentException("Porta deve ser um numero entre 1 e 65535");
    }
}
