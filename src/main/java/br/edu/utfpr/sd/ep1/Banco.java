package br.edu.utfpr.sd.ep1;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.util.HashSet;

/** Estrutura interna de persistencia; os tipos internos nao sao mensagens do protocolo. */
public final class Banco {
    public List<Usuario> usuarios = new ArrayList<>();
    public List<Sessao> sessoes = new ArrayList<>();
    public List<Sala> salas = new ArrayList<>();
    public List<Reserva> reservas = new ArrayList<>();
    public long proximaSalaId = 1;
    public long proximaReservaId = 1;

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

    public static final class Sala {
        public String id;
        public String nome;
        public int capacidade;
        public String local;
        public String status;
        public List<String> recursos = new ArrayList<>();
        public List<String> slots = new ArrayList<>();
    }

    public static final class Reserva {
        public String id;
        public String salaId;
        public String usuarioId;
        public String data;
        public String inicio;
        public String fim;
        public String assunto;
        public int participantes;
        public String createdAt;
    }

    /** Integridade do estado antes do commit, inclusive unicidade do email TEXT. */
    public void validarIntegridade() throws IOException {
        var ids = new HashSet<String>(); var nomes = new HashSet<String>(); var emails = new HashSet<String>();
        for (Usuario u : usuarios) {
            if (u == null || u.id == null || u.user == null || u.email == null || u.senhaHash == null
                    || u.senhaSalt == null || u.role == null || u.createdAt == null
                    || !ids.add(u.id) || !nomes.add(u.user) || !emails.add(u.email))
                throw new IOException("Usuario incompleto ou duplicado");
        }
        var tokens = new HashSet<String>(); var donos = new HashSet<String>();
        for (Sessao s : sessoes) {
            if (s == null || s.token == null || !ids.contains(s.usuarioId)
                    || !tokens.add(s.token) || !donos.add(s.usuarioId)) throw new IOException("Sessao inconsistente");
        }
        var salasIds = new HashSet<String>(); var salasNomes = new HashSet<String>();
        for (Sala s : salas) {
            if (s == null || s.id == null || s.nome == null || !salasIds.add(s.id) || !salasNomes.add(s.nome)
                    || s.local == null || s.status == null || s.recursos == null || s.slots == null)
                throw new IOException("Sala inconsistente");
        }
        var reservasIds = new HashSet<String>();
        for (Reserva r : reservas) {
            if (r == null || r.id == null || !reservasIds.add(r.id) || !ids.contains(r.usuarioId)
                    || !salasIds.contains(r.salaId) || r.data == null || r.inicio == null || r.fim == null
                    || r.assunto == null || r.createdAt == null) throw new IOException("Reserva inconsistente");
        }
    }
}
