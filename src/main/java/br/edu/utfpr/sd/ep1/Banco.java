package br.edu.utfpr.sd.ep1;

import java.util.ArrayList;
import java.util.List;

/** Estrutura interna de persistencia; os tipos internos nao sao mensagens do protocolo. */
public final class Banco {
    public List<Usuario> usuarios = new ArrayList<>();
    public List<Sessao> sessoes = new ArrayList<>();

    public Banco copiar() {
        return Protocolo.GSON.fromJson(Protocolo.GSON.toJson(this), Banco.class);
    }

    public static final class Usuario {
        public String id;
        public String user;
        public String email;
        public String senhaHash;
        public String senhaSalt;
        public String role;
        public String createdAt;
    }

    public static final class Sessao {
        public String token;
        public String usuarioId;
        public long ultimoUso;
    }
}
