package br.edu.utfpr.sd.ep1;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Clica os formularios Swing da EP-2 com TCP e MySQL reais. Exige ambiente grafico. */
@SuppressWarnings("try")
public final class TesteGuiEp2 {
    private static ClienteGui tela;
    private static int verificacoes;
    private TesteGuiEp2() { }

    public static void main(String[] args) throws Exception {
        try (SuporteMySqlTeste banco = new SuporteMySqlTeste(args)) {
            banco.limpar();
            try (ServidorTcp servidor = new ServidorTcp(0, new UsuarioService(banco.repositorio, Clock.systemUTC()), new PrintStream(OutputStream.nullOutputStream()))) {
                Thread thread = new Thread(() -> { try { servidor.executar(); } catch (java.io.IOException e) { throw new IllegalStateException(e); } });
                thread.start();
                try {
                    new UsuarioService(banco.repositorio, Clock.systemUTC()).executar(Protocolo.interpretar(
                            "{\"op\":\"register\",\"user\":\"guiusuario\",\"email\":\"gui@teste.com\",\"password\":\"senha123\"}"));
                    SwingUtilities.invokeAndWait(() -> { tela = new ClienteGui(new String[] {"127.0.0.1",Integer.toString(servidor.porta())}); tela.setVisible(true); });
                    preencher("email","admin@admin.com"); preencher("senha","admin"); clicar("entrar","200");
                    SwingUtilities.invokeAndWait(() -> {
                        JTabbedPane abas=(JTabbedPane) componente(tela,"abas");
                        conferir(abas.isEnabledAt(1),"Admin acessa aba de usuarios"); abas.setSelectedIndex(1);
                    });
                    clicar("admin_list_users","200"); conferir(linhas("usuariosTabela")==2,"Tabela de usuarios mostra cadastros reais");
                    formulario("admin_update_user",Map.of("target_user","guiusuario","user","guioutro","password","","role",""),"200");
                    conferir(banco.repositorio.carregar().usuarios.stream().anyMatch(u->u.user.equals("guioutro")),"Atualizacao administrativa persistida");
                    formulario("admin_read_user",Map.of("target_user","guioutro"),"200");
                    SwingUtilities.invokeAndWait(() -> ((JTabbedPane)componente(tela,"abas")).setSelectedIndex(2));
                    formulario("create_room",Map.of("name","Sala GUI","capacity","6","location","Bloco A","resources","projetor","2M1","true","2M2","true"),"201");
                    String id=banco.repositorio.carregar().salas.get(0).id;
                    formulario("list_rooms",Map.of(),"200"); conferir(linhas("salasTabela")==1,"Listagem grafica de salas");
                    String data=LocalDate.now(SalaService.FUSO).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString();
                    formulario("read_room",Map.of("room_id",id,"date",data),"200");
                    conferir(linhas("salasTabela")==2,"Agenda por horario exibida na GUI");
                    SwingUtilities.invokeAndWait(() -> {
                        try {
                            BufferedImage imagem=new BufferedImage(tela.getWidth(),tela.getHeight(),BufferedImage.TYPE_INT_RGB);
                            var g=imagem.createGraphics();try {tela.paint(g);}finally{g.dispose();}
                            ImageIO.write(imagem,"png",Path.of("build/gui-ep2.png").toFile());
                        } catch(java.io.IOException e){throw new IllegalStateException(e);}
                    });
                    formulario("update_room",Map.of("room_id",id,"name","Sala GUI Nova","capacity","12","room_status","inactive"),"200");
                    var sala=banco.repositorio.carregar().salas.get(0);
                    conferir(sala.nome.equals("Sala GUI Nova")&&sala.capacidade==12&&sala.status.equals("inactive")&&sala.slots.size()==2,"Atualizacao de sala preserva slots vazios");
                    formulario("delete_room",Map.of("room_id",id),"200");
                    formulario("list_rooms",Map.of(),"200"); conferir(linhas("salasTabela")==0,"Sala excluida desaparece da tabela");
                    SwingUtilities.invokeAndWait(() -> ((JTabbedPane)componente(tela,"abas")).setSelectedIndex(1));
                    formulario("admin_delete_user",Map.of("target_user","guioutro"),"200");
                    clicar("admin_list_users","200"); conferir(linhas("usuariosTabela")==1,"Exclusao administrativa refletida na GUI");
                    clicar("logout","200");
                    SwingUtilities.invokeAndWait(() -> conferir(!((JTabbedPane)componente(tela,"abas")).isEnabledAt(1),"Logout remove acesso visual administrativo"));
                } finally {
                    if(tela!=null) SwingUtilities.invokeAndWait(tela::dispose);
                    servidor.close();thread.join(3000);banco.limpar();
                }
            }
        }
        System.out.println("GUI EP-2 MySQL: "+verificacoes+" verificacoes aprovadas.");
    }

    private static Component componente(Container pai,String nome){
        for(Component filho:pai.getComponents()){
            if(nome.equals(filho.getName()))return filho;
            if(filho instanceof Container c){Component achado=componente(c,nome);if(achado!=null)return achado;}
        }return null;
    }
    private static void conferir(boolean ok,String msg){verificacoes++;if(!ok)throw new AssertionError(msg);}
    private static void preencher(String campo,String valor)throws Exception{
        SwingUtilities.invokeAndWait(()->((JTextComponent)componente(tela,campo)).setText(valor));
    }
    private static int linhas(String tabela)throws Exception{
        int[] n={0};SwingUtilities.invokeAndWait(()->n[0]=((JTable)componente(tela,tabela)).getRowCount());return n[0];
    }
    private static void clicar(String botao,String status)throws Exception{
        SwingUtilities.invokeAndWait(()->((JButton)componente(tela,botao)).doClick());
        long prazo=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
        while(true){
            boolean[] pronto={false};SwingUtilities.invokeAndWait(()->pronto[0]=componente(tela,botao).isEnabled());
            if(pronto[0])break;if(System.nanoTime()>prazo)throw new AssertionError("GUI bloqueada: "+botao);Thread.sleep(20);
        }
        SwingUtilities.invokeAndWait(()->conferir(((JLabel)componente(tela,"status")).getText().startsWith(status),"Resposta GUI "+botao+": "+((JLabel)componente(tela,"status")).getText()));
    }
    private static void formulario(String op,Map<String,String> valores,String status)throws Exception{
        SwingUtilities.invokeAndWait(()->{
            Timer timer=new Timer(80,null);
            timer.addActionListener(e->{
                for(Window janela:Window.getWindows())if(janela instanceof JDialog dialog&&dialog.isShowing()){
                    if(dialog.getTitle().equals("Confirmação")){
                        JOptionPane pane=encontrarPane(dialog);if(pane!=null){pane.setValue(JOptionPane.YES_OPTION);timer.stop();}return;
                    }
                    if(!dialog.getTitle().equals(OperacoesCliente.TITULOS.get(op)))continue;
                    valores.forEach((campo,valor)->{
                        Component c=componente(dialog,campo);
                        if(c instanceof JTextComponent t)t.setText(valor);
                        if(c instanceof JCheckBox b)b.setSelected(Boolean.parseBoolean(valor));
                    });
                    JOptionPane pane=encontrarPane(dialog);
                    if(pane!=null){pane.setValue(JOptionPane.OK_OPTION);if(!op.startsWith("delete_")&&!op.equals("admin_delete_user"))timer.stop();}
                }
            });timer.start();
        });
        clicar(op,status);
    }
    private static JOptionPane encontrarPane(Container c){
        for(Component filho:c.getComponents()){
            if(filho instanceof JOptionPane pane)return pane;
            if(filho instanceof Container pai){JOptionPane pane=encontrarPane(pai);if(pane!=null)return pane;}
        }return null;
    }
}
