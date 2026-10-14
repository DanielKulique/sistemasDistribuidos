package br.edu.utfpr.sd.ep1;

import java.io.IOException;

/** Uma resposta completa invalida nao autoriza reenviar a operacao. */
public final class ErroProtocoloException extends IOException {
    private static final long serialVersionUID = 1L;

    public ErroProtocoloException(String mensagem) { super(mensagem); }
    public ErroProtocoloException(String mensagem, Throwable causa) { super(mensagem, causa); }
}
