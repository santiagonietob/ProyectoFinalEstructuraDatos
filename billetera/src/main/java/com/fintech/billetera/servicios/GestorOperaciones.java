package com.fintech.billetera.servicios;

import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import com.fintech.billetera.estructuras.ArbolFidelizacion;
import com.fintech.billetera.estructuras.ColaNotificaciones;
import com.fintech.billetera.estructuras.ColaPrioridad;
import com.fintech.billetera.estructuras.GrafoTransacciones;
import com.fintech.billetera.estructuras.HistorialTransacciones;
import com.fintech.billetera.estructuras.PilaReversiones;
import com.fintech.billetera.modelos.Alerta;
import com.fintech.billetera.modelos.Billetera;
import com.fintech.billetera.modelos.EstadoBilletera;
import com.fintech.billetera.modelos.EstadoTransaccion;
import com.fintech.billetera.modelos.Frecuencia;
import com.fintech.billetera.modelos.NivelRiesgo;
import com.fintech.billetera.modelos.NivelUsuario;
import com.fintech.billetera.modelos.TipoAlerta;
import com.fintech.billetera.modelos.TipoTransaccion;
import com.fintech.billetera.modelos.Transaccion;
import com.fintech.billetera.modelos.TxnProgramada;
import com.fintech.billetera.modelos.Usuario;
import com.fintech.billetera.repositorios.BilleteraRepositorio;
import com.fintech.billetera.repositorios.TransaccionRepositorio;
import com.fintech.billetera.repositorios.UsuarioRepositorio;
import com.fintech.billetera.repositorios.AuditoriaRepositorio;

import jakarta.annotation.PostConstruct;

@Service
public class GestorOperaciones {

    @Autowired
    private UsuarioRepositorio usuarioRepo;

    @Autowired
    private BilleteraRepositorio billeteraRepo;

    @Autowired
    private TransaccionRepositorio transaccionRepo;

    @Autowired
    private AuditoriaRepositorio auditoriaRepo;

    private ColaPrioridad colaProgramadas = new ColaPrioridad();

    public ColaPrioridad getColaProgramadas() {
        return colaProgramadas;
    }

    private PilaReversiones pilaReversiones = new PilaReversiones();
    private ColaNotificaciones colaNotificaciones = new ColaNotificaciones();
    private GrafoTransacciones grafo = new GrafoTransacciones();
    private ArbolFidelizacion arbol = new ArbolFidelizacion();
    private SistemaRecompensas sistemaRecompensas = new SistemaRecompensas();
    private DetectorComportamiento detector;
    private MotorAnalitica analitica = new MotorAnalitica();

    public void registrarUsuario(@NonNull Usuario usuario) {
        List<Usuario> usuarios = usuarioRepo.findAll();

        for (Usuario u : usuarios) {

            if (u.getEmail().equalsIgnoreCase(usuario.getEmail())) {

                System.out.println("Email ya registrado.");

                return;
            }
        }
        usuarioRepo.save(usuario);
        grafo.agregarVertice(usuario);
        arbol.actualizar(usuario);
        System.out.println("Usuario registrado: " + usuario.getNombre());
    }

    public void registrarBilletera(@NonNull Billetera billetera) {
        billeteraRepo.save(billetera);
        System.out.println("Billetera registrada: " + billetera.getNombre());
    }

    public boolean procesarTransaccion(Transaccion txn) {
        if (txn.getValor() <= 0) {

            txn.setEstado(EstadoTransaccion.RECHAZADA);

            transaccionRepo.save(txn);

            return false;
        }
        Billetera origen = txn.getBilleteraOrigenId() != null
                ? billeteraRepo.findById(txn.getBilleteraOrigenId()).orElse(null)
                : null;

        Billetera destino = txn.getBilleteraDestinoId() != null
                ? billeteraRepo.findById(txn.getBilleteraDestinoId()).orElse(null)
                : null;
        if (origen != null &&
                destino != null &&
                origen.getId().equals(destino.getId())) {

            txn.setEstado(EstadoTransaccion.RECHAZADA);

            transaccionRepo.save(txn);

            return false;
        }
        if ((origen != null &&
                origen.getEstado() == EstadoBilletera.BLOQUEADA) ||

                (destino != null &&
                        destino.getEstado() == EstadoBilletera.BLOQUEADA)) {

            txn.setEstado(EstadoTransaccion.RECHAZADA);

            transaccionRepo.save(txn);

            System.out.println("Billetera bloqueada.");

            return false;
        }

        String usuarioId = origen != null
                ? origen.getUsuarioId()
                : destino != null ? destino.getUsuarioId() : null;

        txn.setUsuarioId(usuarioId);

        if (txn.getTipo() == TipoTransaccion.RETIRO ||
                txn.getTipo() == TipoTransaccion.TRANSFERENCIA) {

            if (origen == null || !origen.validarSaldo(txn.getValor())) {
                txn.setEstado(EstadoTransaccion.RECHAZADA);

                transaccionRepo.save(txn);

                generarAlerta(new Alerta(
                        "A" + System.currentTimeMillis(),
                        TipoAlerta.OPERACION_RECHAZADA,
                        "Saldo insuficiente para: " + txn.getId(),
                        usuarioId));

                return false;
            }
        }

        ejecutarMovimiento(txn, origen, destino);

        Usuario usuario = usuarioId != null
                ? usuarioRepo.findById(usuarioId).orElse(null)
                : null;

        if (usuario != null) {
            List<Transaccion> historialLista = transaccionRepo
                    .findByUsuarioIdOrderByFechaDesc(usuarioId);

            HistorialTransacciones historial = new HistorialTransacciones();

            for (Transaccion t : historialLista) {
                historial.agregar(t);
            }

            NivelRiesgo riesgo = detector.analizarTransaccion(txn, historial, usuario);

            if (riesgo != NivelRiesgo.BAJO) {
                generarAlerta(new Alerta(
                        "A" + System.currentTimeMillis(),
                        TipoAlerta.RIESGO_DETECTADO,
                        "IA detectó una transacción sospechosa: " + txn.getId() + " | Riesgo: " + riesgo,
                        usuarioId,
                        riesgo));
            }

            int puntos = sistemaRecompensas.calcularPuntos(txn);
            NivelUsuario nivelAnterior = usuario.getNivel();

            usuario.acumularPuntos(puntos);
            usuarioRepo.save(usuario);
            arbol.actualizar(usuario);

            if (usuario.getNivel() != nivelAnterior) {
                generarAlerta(new Alerta(
                        "A" + System.currentTimeMillis(),
                        TipoAlerta.ASCENSO_NIVEL,
                        "Subiste a nivel: " + usuario.getNivel(),
                        usuarioId));
            }

            if ((txn.getTipo() == TipoTransaccion.TRANSFERENCIA ||
                    txn.getTipo() == TipoTransaccion.PAGO_PROGRAMADO) && destino != null) {

                String uidDestino = destino.getUsuarioId();

                if (usuarioId != null &&
                        uidDestino != null &&
                        !usuarioId.equals(uidDestino)) {

                    grafo.agregarArista(usuarioId, uidDestino, txn.getValor());

                    // Crear registro espejo para el historial del usuario destino
                    Transaccion txnEspejo = new Transaccion(
                            txn.getId() + "-R",
                            txn.getTipo(),
                            txn.getValor(),
                            txn.getBilleteraOrigenId(),
                            txn.getBilleteraDestinoId());
                    txnEspejo.setEstado(EstadoTransaccion.COMPLETADA);
                    txnEspejo.setUsuarioId(uidDestino);
                    transaccionRepo.save(txnEspejo);
                }
            }

            pilaReversiones.push(txn);
            verificarSaldoBajo(origen, usuarioId);
        }

        transaccionRepo.save(txn);
        return true;
    }

    @PostConstruct
    public void reconstruirEstructurasDesdeBD() {
        detector = new DetectorComportamiento(auditoriaRepo);
        System.out.println("Reconstruyendo estructuras desde la base de datos...");

        grafo = new GrafoTransacciones();
        arbol = new ArbolFidelizacion();
        pilaReversiones = new PilaReversiones();

        List<Usuario> usuarios = usuarioRepo.findAll();

        for (Usuario usuario : usuarios) {
            grafo.agregarVertice(usuario);
            arbol.insertar(usuario);
        }

        List<Transaccion> transacciones = transaccionRepo.findAll();

        transacciones.sort(Comparator.comparing(Transaccion::getFecha));

        for (Transaccion txn : transacciones) {
            if (txn.getEstado() == EstadoTransaccion.REVERTIDA ||
                    txn.getEstado() == EstadoTransaccion.RECHAZADA) {
                continue;
            }

            if (txn.getTipo() == TipoTransaccion.TRANSFERENCIA ||
                    txn.getTipo() == TipoTransaccion.PAGO_PROGRAMADO) {

                Billetera origen = txn.getBilleteraOrigenId() != null
                        ? billeteraRepo.findById(txn.getBilleteraOrigenId()).orElse(null)
                        : null;

                Billetera destino = txn.getBilleteraDestinoId() != null
                        ? billeteraRepo.findById(txn.getBilleteraDestinoId()).orElse(null)
                        : null;

                if (origen != null && destino != null) {
                    String usuarioOrigenId = origen.getUsuarioId();
                    String usuarioDestinoId = destino.getUsuarioId();

                    if (usuarioOrigenId != null &&
                            usuarioDestinoId != null &&
                            !usuarioOrigenId.equals(usuarioDestinoId)) {

                        grafo.agregarArista(usuarioOrigenId, usuarioDestinoId, txn.getValor());
                    }
                }
            }

            if (txn.getEstado() == EstadoTransaccion.COMPLETADA) {
                pilaReversiones.push(txn);
            }
        }

        // Recargar historial de auditoría desde BD
        List<com.fintech.billetera.modelos.AuditoriaEvento> eventos = auditoriaRepo.findAll();
        for (com.fintech.billetera.modelos.AuditoriaEvento evento : eventos) {
            detector.getHistorialAuditoria().agregar(evento.getEvento());
        }

        System.out.println("Estructuras reconstruidas: "
                + usuarios.size() + " usuarios, "
                + transacciones.size() + " transacciones, "
                + grafo.getTotalAristas() + " relaciones en el grafo.");
    }

    private void ejecutarMovimiento(Transaccion txn, Billetera origen, Billetera destino) {
        switch (txn.getTipo()) {
            case RECARGA:
                if (destino != null) {
                    destino.recargar(txn.getValor());
                    billeteraRepo.save(destino);
                }
                break;
            case RETIRO:
                if (origen != null) {
                    origen.retirar(txn.getValor());
                    billeteraRepo.save(origen);
                }
                break;
            case TRANSFERENCIA:
            case PAGO_PROGRAMADO:
                if (origen != null) {
                    origen.retirar(txn.getValor());
                    billeteraRepo.save(origen);
                }
                if (destino != null) {
                    destino.recargar(txn.getValor());
                    billeteraRepo.save(destino);
                }
                break;
        }
        txn.setEstado(EstadoTransaccion.COMPLETADA);
    }

    public boolean revertirUltimaTransaccion() {
        if (!pilaReversiones.puedeRevertir()) {
            System.out.println("No hay transacciones para revertir.");
            return false;
        }
        Transaccion txn = pilaReversiones.pop();

        if (txn.getEstado() == EstadoTransaccion.REVERTIDA) {
            return false;
        }
        Billetera origen = txn.getBilleteraOrigenId() != null
                ? billeteraRepo.findById(txn.getBilleteraOrigenId()).orElse(null)
                : null;
        Billetera destino = txn.getBilleteraDestinoId() != null
                ? billeteraRepo.findById(txn.getBilleteraDestinoId()).orElse(null)
                : null;

        switch (txn.getTipo()) {
            case RECARGA:
                if (destino != null) {
                    destino.retirar(txn.getValor());
                    billeteraRepo.save(destino);
                }
                break;
            case RETIRO:
                if (origen != null) {
                    origen.recargar(txn.getValor());
                    billeteraRepo.save(origen);
                }
                break;
            case TRANSFERENCIA:
            case PAGO_PROGRAMADO:
                if (origen != null) {
                    origen.recargar(txn.getValor());
                    billeteraRepo.save(origen);
                }
                if (destino != null) {
                    destino.retirar(txn.getValor());
                    billeteraRepo.save(destino);
                }
                break;
        }

        txn.setEstado(EstadoTransaccion.REVERTIDA);
        transaccionRepo.save(txn);

        String usuarioId = txn.getUsuarioId();
        Usuario usuario = usuarioId != null ? usuarioRepo.findById(usuarioId).orElse(null) : null;
        if (usuario != null) {
            sistemaRecompensas.recalcularAlRevertir(usuario, txn);
            usuarioRepo.save(usuario);
            arbol.actualizar(usuario);
            generarAlerta(new Alerta("A" + System.currentTimeMillis(),
                    TipoAlerta.TRANSACCION_REVERTIDA,
                    "Transaccion revertida: " + txn.getId(), usuarioId));
        }
        System.out.println("Transaccion revertida: " + txn.getId());
        return true;
    }

    public void programarTransaccion(TxnProgramada txn) {
        if (txn.getFechaEjecucion().before(new java.util.Date())) {
            System.out.println("Fecha inválida.");
            return;
        }

        if (txn.getBilleteraOrigenId() != null) {
            Billetera origen = billeteraRepo.findById(txn.getBilleteraOrigenId()).orElse(null);
            if (origen == null) {
                System.out.println("Billetera origen no existe.");
                return;
            }
            if (origen.getEstado() == com.fintech.billetera.modelos.EstadoBilletera.BLOQUEADA) {
                System.out.println("Billetera origen bloqueada.");
                return;
            }
            if (!origen.validarSaldo(txn.getValor())) {
                System.out.println("Saldo insuficiente para programar la transacción.");
                return;
            }
        }

        colaProgramadas.agregar(txn);
        System.out.println("Transacción programada correctamente: " + txn.getId());
    }

    public void ejecutarProgramadas() {

        while (!colaProgramadas.estaVacia() &&
                colaProgramadas.peek().estaListaParaEjecutar()) {

            TxnProgramada txn = colaProgramadas.poll();

            Transaccion transaccion = new Transaccion(
                    txn.getId(),
                    txn.getTipo(),
                    txn.getValor(),
                    txn.getBilleteraOrigenId(),
                    txn.getBilleteraDestinoId());

            transaccion.setUsuarioId(txn.getUsuarioId());

            procesarTransaccion(transaccion);

            if (txn.getFrecuencia() == Frecuencia.SEMANAL) {

                txn.setFechaEjecucion(
                        new java.util.Date(
                                txn.getFechaEjecucion().getTime()
                                        + (7L * 24 * 60 * 60 * 1000)));

                colaProgramadas.agregar(txn);
            }

            if (txn.getFrecuencia() == Frecuencia.MENSUAL) {

                txn.setFechaEjecucion(
                        new java.util.Date(
                                txn.getFechaEjecucion().getTime()
                                        + (30L * 24 * 60 * 60 * 1000)));

                colaProgramadas.agregar(txn);
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void ejecutarProgramadasAutomaticamente() {

        System.out.println(">>> Ejecutando programadas...");

        ejecutarProgramadas();
    }

    private void verificarSaldoBajo(Billetera billetera, String usuarioId) {
        if (billetera != null && billetera.getSaldo() < 50000) {
            generarAlerta(new Alerta("A" + System.currentTimeMillis(),
                    TipoAlerta.SALDO_BAJO,
                    "Saldo bajo en billetera: " + billetera.getNombre(), usuarioId));
        }
    }

    public void generarAlerta(Alerta alerta) {
        colaNotificaciones.encolar(alerta);
        System.out.println("[ALERTA] " + alerta.getMensaje());
    }

    public void despacharAlertas() {
        while (!colaNotificaciones.estaVacia()) {
            Alerta a = colaNotificaciones.despachar();
            System.out.println("[NOTIF] " + a);
        }
    }

    public List<Usuario> getTodosUsuarios() {
        return usuarioRepo.findAll();
    }

    public Usuario getUsuario(String id) {
        return usuarioRepo.findById(id).orElse(null);
    }

    public List<Billetera> getTodasBilleteras() {
        return billeteraRepo.findAll();
    }

    public Billetera getBilletera(String id) {
        return billeteraRepo.findById(id).orElse(null);
    }

    public List<Transaccion> getHistorial(String usuarioId) {
        return transaccionRepo.findByUsuarioIdOrderByFechaDesc(usuarioId);
    }

    public GrafoTransacciones getGrafo() {
        return grafo;
    }

    public ArbolFidelizacion getArbol() {
        return arbol;
    }

    public MotorAnalitica getAnalitica() {
        return analitica;
    }

    public SistemaRecompensas getSistemaRecompensas() {
        return sistemaRecompensas;
    }

    public ColaNotificaciones getColaNotificaciones() {
        return colaNotificaciones;
    }

    public void eliminarUsuario(String id) {
        usuarioRepo.deleteById(id);
    }

    public List<Billetera> getBilleterasDeUsuario(String usuarioId) {
        return billeteraRepo.findByUsuarioId(usuarioId);
    }

    public List<Transaccion> getTodasTransacciones() {
        return transaccionRepo.findAll();
    }

    public DetectorComportamiento getDetector() {
        return detector;
    }

    public boolean revertirTransaccionPorId(String id) {

        Transaccion txn = transaccionRepo.findById(id).orElse(null);

        if (txn == null) {
            return false;
        }

        if (txn.getEstado() == EstadoTransaccion.REVERTIDA) {
            return false;
        }

        Billetera origen = txn.getBilleteraOrigenId() != null
                ? billeteraRepo.findById(txn.getBilleteraOrigenId()).orElse(null)
                : null;

        Billetera destino = txn.getBilleteraDestinoId() != null
                ? billeteraRepo.findById(txn.getBilleteraDestinoId()).orElse(null)
                : null;

        switch (txn.getTipo()) {

            case RECARGA:

                if (destino != null) {
                    destino.retirar(txn.getValor());
                    billeteraRepo.save(destino);
                }

                break;

            case RETIRO:

                if (origen != null) {
                    origen.recargar(txn.getValor());
                    billeteraRepo.save(origen);
                }

                break;

            case TRANSFERENCIA:
            case PAGO_PROGRAMADO:

                if (origen != null) {
                    origen.recargar(txn.getValor());
                    billeteraRepo.save(origen);
                }

                if (destino != null) {
                    destino.retirar(txn.getValor());
                    billeteraRepo.save(destino);
                }

                break;
        }

        txn.setEstado(EstadoTransaccion.REVERTIDA);

        transaccionRepo.save(txn);

        Usuario usuario = usuarioRepo.findById(txn.getUsuarioId()).orElse(null);

        if (usuario != null) {

            sistemaRecompensas.recalcularAlRevertir(usuario, txn);

            usuarioRepo.save(usuario);

            arbol.actualizar(usuario);
        }

        return true;
    }

    public boolean canjearBeneficioUsuario(String usuarioId, String beneficioId) {

        Usuario usuario = usuarioRepo.findById(usuarioId).orElse(null);

        if (usuario == null) {
            return false;
        }

        boolean canjeado = sistemaRecompensas
                .canjearBeneficio(usuario, beneficioId);

        if (canjeado) {

            usuarioRepo.save(usuario);

            generarAlerta(new Alerta(
                    "A" + System.currentTimeMillis(),
                    TipoAlerta.BENEFICIO_CANJEADO,
                    "Canjeaste el beneficio: " + beneficioId,
                    usuarioId));
        }

        return canjeado;
    }

    public void actualizarUsuario(Usuario usuario) {
        usuarioRepo.save(usuario);
        arbol.actualizar(usuario);

        System.out.println("Usuario actualizado: " + usuario.getNombre());
    }
}