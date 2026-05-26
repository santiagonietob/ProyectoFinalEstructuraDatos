package com.fintech.billetera.controladores;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import com.fintech.billetera.modelos.EstadoBilletera;
import com.fintech.billetera.estructuras.ArbolFidelizacion;
import com.fintech.billetera.estructuras.ColaNotificaciones;
import com.fintech.billetera.estructuras.PilaReversiones;
import com.fintech.billetera.modelos.Alerta;
import com.fintech.billetera.modelos.Billetera;
import com.fintech.billetera.modelos.EstadoTransaccion;
import com.fintech.billetera.modelos.TipoAlerta;
import com.fintech.billetera.modelos.TipoBilletera;
import com.fintech.billetera.modelos.TipoTransaccion;
import com.fintech.billetera.modelos.Transaccion;
import com.fintech.billetera.modelos.TxnProgramada;
import com.fintech.billetera.modelos.Usuario;
import com.fintech.billetera.servicios.GestorOperaciones;

@Controller
public class BilleteraController {
    private boolean billeteraPerteneceAUsuario(Billetera billetera, String usuarioId) {
        return billetera != null
                && usuarioId != null
                && usuarioId.equals(billetera.getUsuarioId());
    }

    @Autowired
    private GestorOperaciones gestor;

    @GetMapping("/")
    public String inicio(Model model) {
        model.addAttribute("usuarios", gestor.getTodosUsuarios());
        model.addAttribute("totalUsuarios", gestor.getTodosUsuarios().size());
        model.addAttribute("totalBilleteras", gestor.getTodasBilleteras().size());
        return "index";
    }

 @PostMapping("/usuario/registrar")
public String registrarUsuario(@RequestParam String id,
        @RequestParam String nombre,
        @RequestParam String email,
        @RequestParam String telefono,
        org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

    Usuario existente = gestor.getUsuario(id);

    if (existente != null) {
        attrs.addFlashAttribute("toastError", "Ya existe un usuario registrado con el ID: " + id);
        return "redirect:/";
    }

    Usuario u = new Usuario(id, nombre, email, telefono);
    gestor.registrarUsuario(u);

    attrs.addFlashAttribute("toast", "Usuario registrado correctamente");
    return "redirect:/";
}


    @PostMapping("/usuario/eliminar")
    public String eliminarUsuario(@RequestParam String id) {
        gestor.eliminarUsuario(id);
        return "redirect:/";
    }

    @PostMapping("/billetera/crear")
    public String crearBilletera(@RequestParam String nombre,
            @RequestParam String tipo,
            @RequestParam String usuarioId,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

        String idGenerado = "B" + System.currentTimeMillis();

        Billetera b = new Billetera(
                idGenerado,
                nombre,
                TipoBilletera.valueOf(tipo),
                usuarioId);

        gestor.registrarBilletera(b);
        attrs.addFlashAttribute("toast", "Billetera creada correctamente");

        return "redirect:/usuarios/" + usuarioId;
    }

    @GetMapping("/api/usuarios/{usuarioId}/billeteras")
    @ResponseBody
    public List<Map<String, Object>> obtenerBilleterasPorUsuario(@PathVariable String usuarioId) {
        List<Map<String, Object>> respuesta = new ArrayList<>();

        Usuario usuario = gestor.getUsuario(usuarioId);

        if (usuario == null) {
            return respuesta;
        }

        List<Billetera> billeteras = gestor.getBilleterasDeUsuario(usuarioId);

        for (Billetera b : billeteras) {
            Map<String, Object> item = new java.util.HashMap<>();

            item.put("id", b.getId());
            item.put("nombre", b.getNombre());
            item.put("tipo", b.getTipo().name());
            item.put("saldo", b.getSaldo());

            respuesta.add(item);
        }

        return respuesta;
    }

    @GetMapping("/usuarios/{id}")
    public String verUsuario(@PathVariable String id, Model model) {
        Usuario u = gestor.getUsuario(id);

        if (u == null) {
            return "redirect:/";
        }

        List<Billetera> billeteras = gestor.getBilleterasDeUsuario(id);
        u.setBilleteras(billeteras);

        model.addAttribute("usuario", u);
        model.addAttribute("historial", gestor.getHistorial(id));
        model.addAttribute("alertas", gestor.getColaNotificaciones().getNoLeidas());

        return "usuario";
    }

   @PostMapping("/transaccion/recarga")
public String recargar(@RequestParam String billeteraId,
        @RequestParam double monto,
        @RequestParam String usuarioId,
        org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

    Billetera billetera = gestor.getBilletera(billeteraId);

    if (billetera == null) {
        attrs.addFlashAttribute("toastError", "No existe la billetera con ID: " + billeteraId);
        return "redirect:/usuarios/" + usuarioId;
    }

    if (!billeteraPerteneceAUsuario(billetera, usuarioId)) {
        attrs.addFlashAttribute("toastError", "Esa billetera no pertenece a este usuario");
        return "redirect:/usuarios/" + usuarioId;
    }

    if (monto <= 0) {
        attrs.addFlashAttribute("toastError", "El monto de recarga debe ser mayor a cero");
        return "redirect:/usuarios/" + usuarioId;
    }

    EstadoBilletera estadoAnterior = billetera.getEstado();
    boolean estabaInactiva = estadoAnterior != EstadoBilletera.ACTIVA;

    if (estabaInactiva) {
        billetera.setEstado(EstadoBilletera.ACTIVA);
        gestor.registrarBilletera(billetera);
    }

    Transaccion t = new Transaccion("T" + System.currentTimeMillis(),
            TipoTransaccion.RECARGA, monto, null, billeteraId);

    boolean exito = gestor.procesarTransaccion(t);

    if (exito && t.getEstado() != EstadoTransaccion.RECHAZADA) {
        if (estabaInactiva) {
            attrs.addFlashAttribute("toast",
                    "Recarga realizada correctamente. La billetera fue activada nuevamente");
        } else {
            attrs.addFlashAttribute("toast", "Recarga realizada correctamente");
        }
    } else {
        if (estabaInactiva) {
            billetera.setEstado(estadoAnterior);
            gestor.registrarBilletera(billetera);
        }

        attrs.addFlashAttribute("toastError", "La recarga fue rechazada");
    }

    return "redirect:/usuarios/" + usuarioId;
}

  @PostMapping("/transaccion/retiro")
public String retirar(@RequestParam String billeteraId,
        @RequestParam double monto,
        @RequestParam String usuarioId,
        org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

    Billetera billetera = gestor.getBilletera(billeteraId);

    if (billetera == null) {
        attrs.addFlashAttribute("toastError", "No existe la billetera con ID: " + billeteraId);
        return "redirect:/usuarios/" + usuarioId;
    }

    if (!billeteraPerteneceAUsuario(billetera, usuarioId)) {
        attrs.addFlashAttribute("toastError", "No puedes retirar de una billetera que no pertenece a este usuario");
        return "redirect:/usuarios/" + usuarioId;
    }

    if (billetera.getEstado() != EstadoBilletera.ACTIVA) {
        attrs.addFlashAttribute("toastError", "No se puede retirar: la billetera está bloqueada o inactiva");
        return "redirect:/usuarios/" + usuarioId;
    }

    Transaccion t = new Transaccion("T" + System.currentTimeMillis(),
            TipoTransaccion.RETIRO, monto, billeteraId, null);

    boolean exito = gestor.procesarTransaccion(t);

    if (exito && t.getEstado() != EstadoTransaccion.RECHAZADA) {
        attrs.addFlashAttribute("toast", "Retiro realizado correctamente");
    } else {
        attrs.addFlashAttribute("toastError", "Retiro rechazado: saldo insuficiente");
    }

    return "redirect:/usuarios/" + usuarioId;
}

    @PostMapping("/transaccion/transferencia")
    public String transferir(@RequestParam String origenId,
            @RequestParam String destinoId,
            @RequestParam double monto,
            @RequestParam String usuarioId,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

        Billetera origen = gestor.getBilletera(origenId);
        Billetera destino = gestor.getBilletera(destinoId);

        if (origen == null) {
            attrs.addFlashAttribute("toastError", "No existe la billetera origen con ID: " + origenId);
            return "redirect:/usuarios/" + usuarioId;
        }

        if (destino == null) {
            attrs.addFlashAttribute("toastError", "No existe la billetera destino con ID: " + destinoId);
            return "redirect:/usuarios/" + usuarioId;
        }

        if (!billeteraPerteneceAUsuario(origen, usuarioId)) {
            attrs.addFlashAttribute("toastError", "La billetera origen no pertenece a este usuario");
            return "redirect:/usuarios/" + usuarioId;
        }

        if (!billeteraPerteneceAUsuario(destino, usuarioId)) {
            attrs.addFlashAttribute("toastError",
                    "Para transferir entre billeteras, la billetera destino también debe ser de este usuario");
            return "redirect:/usuarios/" + usuarioId;
        }
        if (origen.getEstado() != EstadoBilletera.ACTIVA) {
    attrs.addFlashAttribute("toastError", "No se puede transferir: la billetera origen está bloqueada o inactiva");
    return "redirect:/usuarios/" + usuarioId;
}

        Transaccion t = new Transaccion("T" + System.currentTimeMillis(),
                TipoTransaccion.TRANSFERENCIA, monto, origenId, destinoId);

        boolean exito = gestor.procesarTransaccion(t);

        if (exito && t.getEstado() != EstadoTransaccion.RECHAZADA) {
            attrs.addFlashAttribute("toast", "Transferencia realizada correctamente");
        } else {
            attrs.addFlashAttribute("toastError", "Transferencia rechazada: saldo insuficiente");
        }

        return "redirect:/usuarios/" + usuarioId;
    }

    @PostMapping("/transaccion/transferencia-externa")
    public String transferenciaExterna(@RequestParam String usuarioId,
            @RequestParam String origenId,
            @RequestParam String destinoUsuarioId,
            @RequestParam String destinoBilleteraId,
            @RequestParam double monto,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

        Usuario destinoUsuario = gestor.getUsuario(destinoUsuarioId);

        if (destinoUsuario == null) {
            attrs.addFlashAttribute("toastError", "No existe el usuario destino con ID: " + destinoUsuarioId);
            return "redirect:/usuarios/" + usuarioId;
        }

        Billetera origen = gestor.getBilletera(origenId);

        if (origen == null) {
            attrs.addFlashAttribute("toastError", "No existe la billetera origen con ID: " + origenId);
            return "redirect:/usuarios/" + usuarioId;
        }

        if (!billeteraPerteneceAUsuario(origen, usuarioId)) {
            attrs.addFlashAttribute("toastError", "La billetera origen no pertenece a este usuario");
            return "redirect:/usuarios/" + usuarioId;
        }

        Billetera destino = gestor.getBilletera(destinoBilleteraId);

        if (destino == null || !destino.getUsuarioId().equals(destinoUsuarioId)) {
            attrs.addFlashAttribute("toastError", "La billetera destino no pertenece al usuario indicado");
            return "redirect:/usuarios/" + usuarioId;
        }
if (origen.getEstado() != EstadoBilletera.ACTIVA) {
    attrs.addFlashAttribute("toastError", "No se puede transferir: la billetera origen está bloqueada o inactiva");
    return "redirect:/usuarios/" + usuarioId;
}
        Transaccion t = new Transaccion("T" + System.currentTimeMillis(),
                TipoTransaccion.TRANSFERENCIA, monto, origenId, destinoBilleteraId);

        boolean exito = gestor.procesarTransaccion(t);

        if (exito && t.getEstado() != EstadoTransaccion.RECHAZADA) {
            attrs.addFlashAttribute("toast", "Transferencia externa realizada correctamente");
        } else {
            attrs.addFlashAttribute("toastError", "Transferencia externa rechazada: saldo insuficiente");
        }

        return "redirect:/usuarios/" + usuarioId;
    }

    @PostMapping("/transaccion/revertir")
    public String revertir(@RequestParam String usuarioId,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

        boolean exito = gestor.revertirUltimaTransaccion();

        if (exito) {
            attrs.addFlashAttribute("toast", "Última transacción revertida correctamente");
        } else {
            attrs.addFlashAttribute("toastError", "No hay transacciones disponibles para revertir");
        }

        return "redirect:/usuarios/" + usuarioId;
    }

    @GetMapping("/analitica")
    public String analitica(Model model,
            @RequestParam(required = false) String fechaInicio,
            @RequestParam(required = false) String fechaFin) {

        List<Usuario> todosUsuarios = gestor.getTodosUsuarios();
        todosUsuarios.forEach(u -> gestor.getGrafo().agregarVertice(u));
        todosUsuarios.forEach(u -> gestor.getArbol().actualizar(u));

        List<Transaccion> todasTxn = gestor.getTodasTransacciones();

        List<Transaccion> txnFiltradas = todasTxn;
        String fechaInicioVal = fechaInicio;
        String fechaFinVal = fechaFin;

        if (fechaInicio != null && !fechaInicio.isEmpty() &&
                fechaFin != null && !fechaFin.isEmpty()) {
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
                java.util.Date inicio = sdf.parse(fechaInicio);
                java.util.Date fin = sdf.parse(fechaFin);
                fin = new java.util.Date(fin.getTime() + 86400000 - 1);

                final java.util.Date inicioFinal = inicio;
                final java.util.Date finFinal = fin;

                txnFiltradas = todasTxn.stream()
                        .filter(t -> !t.getFecha().before(inicioFinal) && !t.getFecha().after(finFinal))
                        .collect(java.util.stream.Collectors.toList());
            } catch (Exception e) {
                System.out.println("Error parsing fechas: " + e.getMessage());
            }
        }

        com.fintech.billetera.estructuras.ListaSimple<Usuario> topLista = gestor.getArbol().getTopN(5);
        List<Usuario> topUsuarios = new ArrayList<>();
        java.util.Iterator<Usuario> itTop = topLista.iterator();

        while (itTop.hasNext()) {
            topUsuarios.add(itTop.next());
        }

        model.addAttribute("topUsuarios", topUsuarios);

        model.addAttribute("ciclos", gestor.getGrafo().detectarCiclo());
        model.addAttribute("vertices", todosUsuarios.size());
        model.addAttribute("aristas", gestor.getGrafo().getTotalAristas());

        model.addAttribute("totalTransacciones", txnFiltradas.size());
        double montoTotal = txnFiltradas.stream().mapToDouble(Transaccion::getValor).sum();
        model.addAttribute("montoTotal", montoTotal);

        long recargas = txnFiltradas.stream().filter(t -> t.getTipo() == TipoTransaccion.RECARGA).count();
        long retiros = txnFiltradas.stream().filter(t -> t.getTipo() == TipoTransaccion.RETIRO).count();
        long transferencias = txnFiltradas.stream().filter(t -> t.getTipo() == TipoTransaccion.TRANSFERENCIA).count();

        model.addAttribute("recargas", recargas);
        model.addAttribute("retiros", retiros);
        model.addAttribute("transferencias", transferencias);

        List<Transaccion> topTransacciones = txnFiltradas.stream()
                .sorted((t1, t2) -> Double.compare(t2.getValor(), t1.getValor()))
                .limit(5)
                .collect(java.util.stream.Collectors.toList());
        model.addAttribute("topTransacciones", topTransacciones);

        List<Transaccion> ultimasTransacciones = txnFiltradas.stream()
                .sorted((t1, t2) -> t2.getFecha().compareTo(t1.getFecha()))
                .limit(10)
                .collect(java.util.stream.Collectors.toList());
        model.addAttribute("ultimasTransacciones", ultimasTransacciones);

        model.addAttribute("historial", new java.util.ArrayList<>());

        Usuario masActivo = null;
        int maxTxn = 0;

        for (Usuario u : todosUsuarios) {
            final String uid = u.getId();
            long cantidad = txnFiltradas.stream()
                    .filter(t -> uid.equals(t.getUsuarioId()))
                    .count();

            if (cantidad > maxTxn) {
                maxTxn = (int) cantidad;
                masActivo = u;
            }
        }

        model.addAttribute("usuarioMasActivo", masActivo);
        model.addAttribute("txnUsuarioActivo", maxTxn);

        Map<String, Long> conteoActividad = txnFiltradas.stream()
                .flatMap(t -> java.util.stream.Stream.of(t.getBilleteraOrigenId(), t.getBilleteraDestinoId()))
                .filter(id -> id != null)
                .collect(java.util.stream.Collectors.groupingBy(id -> id, java.util.stream.Collectors.counting()));

        List<Map.Entry<String, Long>> billeterasActivas = new ArrayList<>(conteoActividad.entrySet());
        billeterasActivas.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        List<Map<String, Object>> billeterasConInfo = new ArrayList<>();

        for (Map.Entry<String, Long> entry : billeterasActivas) {
            Billetera bil = gestor.getBilletera(entry.getKey());

            if (bil != null) {
                Map<String, Object> info = new java.util.HashMap<>();
                info.put("id", bil.getId());
                info.put("nombre", bil.getNombre());
                info.put("tipo", bil.getTipo());
                info.put("saldo", bil.getSaldo());
                info.put("movimientos", entry.getValue());
                billeterasConInfo.add(info);
            }
        }

        model.addAttribute("billeterasActivas", billeterasConInfo);

        Map<String, String> tablaHashUsuarios = new java.util.LinkedHashMap<>();

        for (Usuario u : todosUsuarios) {
            tablaHashUsuarios.put(u.getId(),
                    u.getNombre() + " | " + u.getNivel() + " | " + u.getPuntosTotales() + " pts");
        }

        Map<String, String> tablaHashBilleteras = new java.util.LinkedHashMap<>();

        for (Billetera b : gestor.getTodasBilleteras()) {
            tablaHashBilleteras.put(b.getId(), b.getNombre() + " | " + b.getTipo() + " | $" + b.getSaldo());
        }

        model.addAttribute("tablaHashUsuarios", tablaHashUsuarios);
        model.addAttribute("tablaHashBilleteras", tablaHashBilleteras);

        List<Transaccion> transaccionesRiesgo = txnFiltradas.stream()
                .filter(t -> t.getEstado() == EstadoTransaccion.COMPLETADA)
                .filter(t -> t.getNivelRiesgo() != null)
                .filter(t -> t.getNivelRiesgo() != com.fintech.billetera.modelos.NivelRiesgo.BAJO)
                .sorted((t1, t2) -> t2.getFecha().compareTo(t1.getFecha()))
                .collect(java.util.stream.Collectors.toList());

        model.addAttribute("transaccionesRiesgo", transaccionesRiesgo);

        List<String> auditorias = new ArrayList<>();
        Iterator<String> itAuditorias = gestor.getDetector().getHistorialAuditoria().iterator();

        while (itAuditorias.hasNext()) {
            auditorias.add((String) itAuditorias.next());
        }

        if (auditorias.isEmpty()) {
            for (Transaccion txn : transaccionesRiesgo) {
                auditorias.add(
                        txn.getFecha()
                                + " - IA detectó riesgo " + txn.getNivelRiesgo()
                                + " en la transacción " + txn.getId()
                                + " del usuario " + txn.getUsuarioId()
                                + ". Tipo: " + txn.getTipo()
                                + ". Monto: $" + txn.getValor());
            }
        }

        model.addAttribute("auditorias", auditorias);
        model.addAttribute("fechaInicio", fechaInicioVal);
        model.addAttribute("fechaFin", fechaFinVal);

        List<Map<String, Object>> rutasFrecuentes = new ArrayList<>();

        for (Usuario u : todosUsuarios) {
            com.fintech.billetera.estructuras.ListaSimple<com.fintech.billetera.estructuras.AristaGrafo> rutas = gestor
                    .getGrafo().getRutasFrecuentes(u.getId());
            java.util.Iterator<com.fintech.billetera.estructuras.AristaGrafo> itRutas = rutas.iterator();

            while (itRutas.hasNext()) {
                com.fintech.billetera.estructuras.AristaGrafo arista = itRutas.next();
                Map<String, Object> ruta = new java.util.HashMap<>();
                Usuario destino = gestor.getUsuario(arista.getDestinoId());

                ruta.put("origen", u.getNombre());
                ruta.put("destino", destino != null ? destino.getNombre() : arista.getDestinoId());
                ruta.put("frecuencia", arista.getFrecuencia());
                ruta.put("montoAcumulado", arista.getMontoAcumulado());
                rutasFrecuentes.add(ruta);
            }
        }

        rutasFrecuentes.sort((a, b) -> Integer.compare(
                (int) b.get("frecuencia"), (int) a.get("frecuencia")));
        model.addAttribute("rutasFrecuentes", rutasFrecuentes);

        List<Map<String, Object>> nodosGrafo = new ArrayList<>();
        List<Map<String, Object>> aristasGrafo = new ArrayList<>();

        for (Usuario u : todosUsuarios) {
            Map<String, Object> nodo = new java.util.HashMap<>();
            nodo.put("id", u.getId());
            nodo.put("label", u.getNombre());
            nodo.put("nivel", u.getNivel().name());
            nodo.put("puntos", u.getPuntosTotales());
            nodosGrafo.add(nodo);
        }

        com.fintech.billetera.estructuras.MapaHash<String, com.fintech.billetera.estructuras.ListaSimple<com.fintech.billetera.estructuras.AristaGrafo>> listaAdj = gestor
                .getGrafo().getListaAdyacencia();

        com.fintech.billetera.estructuras.ListaSimple<String> claves = listaAdj.claves();
        java.util.Iterator<String> itClaves = claves.iterator();

        while (itClaves.hasNext()) {
            String origenId = itClaves.next();
            com.fintech.billetera.estructuras.ListaSimple<com.fintech.billetera.estructuras.AristaGrafo> aristas = listaAdj
                    .obtener(origenId);

            if (aristas != null) {
                java.util.Iterator<com.fintech.billetera.estructuras.AristaGrafo> itA = aristas.iterator();

                while (itA.hasNext()) {
                    com.fintech.billetera.estructuras.AristaGrafo arista = itA.next();
                    Map<String, Object> a = new java.util.HashMap<>();
                    a.put("from", arista.getOrigenId());
                    a.put("to", arista.getDestinoId());
                    a.put("monto", arista.getMontoAcumulado());
                    a.put("frecuencia", arista.getFrecuencia());
                    aristasGrafo.add(a);
                }
            }
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        try {
            model.addAttribute("nodosGrafoJson", mapper.writeValueAsString(nodosGrafo));
            model.addAttribute("aristasGrafoJson", mapper.writeValueAsString(aristasGrafo));
        } catch (Exception e) {
            model.addAttribute("nodosGrafoJson", "[]");
            model.addAttribute("aristasGrafoJson", "[]");
        }

        return "analitica";
    }

    @GetMapping("/usuario/buscar")
    public String buscarUsuario(@RequestParam String id, Model model) {
        Usuario u = gestor.getUsuario(id);

        if (u == null) {
            model.addAttribute("usuarios", gestor.getTodosUsuarios());
            model.addAttribute("totalUsuarios", gestor.getTodosUsuarios().size());
            model.addAttribute("totalBilleteras", gestor.getTodasBilleteras().size());
            model.addAttribute("errorBusqueda", "No se encontró ningún usuario con ID: " + id);
            return "index";
        }

        return "redirect:/usuarios/" + u.getId();
    }

    @PostMapping("/usuario/modificar")
    public String modificarUsuario(@RequestParam String id,
            @RequestParam String nombre,
            @RequestParam String email,
            @RequestParam String telefono,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

        Usuario u = gestor.getUsuario(id);

        if (u != null) {
            u.setNombre(nombre);
            u.setEmail(email);
            u.setTelefono(telefono);
            gestor.registrarUsuario(u);
            attrs.addFlashAttribute("toast", "Usuario actualizado correctamente");
        } else {
            attrs.addFlashAttribute("toastError", "No se encontró el usuario");
        }

        return "redirect:/usuarios/" + id;
    }

    @PostMapping("/transaccion/programar")
    public String programarTransaccion(@RequestParam String usuarioId,
            @RequestParam String origenId,
            @RequestParam String destinoId,
            @RequestParam double monto,
            @RequestParam String fechaEjecucion,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm");
            java.util.Date fecha = sdf.parse(fechaEjecucion);

            TxnProgramada txn = new TxnProgramada(
                    "TP" + System.currentTimeMillis(),
                    TipoTransaccion.PAGO_PROGRAMADO,
                    monto, origenId, destinoId, fecha, "manual");

            txn.setUsuarioId(usuarioId);
            gestor.programarTransaccion(txn);
            attrs.addFlashAttribute("toast", "Transacción programada correctamente");
        } catch (Exception e) {
            attrs.addFlashAttribute("toastError", "Error al programar la transacción");
            System.out.println("Error al programar: " + e.getMessage());
        }

        return "redirect:/usuarios/" + usuarioId;
    }

    @PostMapping("/transaccion/ejecutarProgramadas")
    public String ejecutarProgramadas(@RequestParam String usuarioId,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

        gestor.ejecutarProgramadas();
        attrs.addFlashAttribute("toast", "Operaciones programadas ejecutadas");

        return "redirect:/usuarios/" + usuarioId;
    }

    @GetMapping("/beneficios/{usuarioId}")
    public String verBeneficios(@PathVariable String usuarioId, Model model) {
        Usuario u = gestor.getUsuario(usuarioId);

        if (u == null) {
            return "redirect:/";
        }

        List<Billetera> billeteras = gestor.getBilleterasDeUsuario(usuarioId);
        u.setBilleteras(billeteras);

        List<Object> beneficiosDisponibles = new ArrayList<>();
        java.util.Iterator<?> itDisponibles = gestor.getSistemaRecompensas()
                .getBeneficiosDisponibles(u)
                .iterator();

        while (itDisponibles.hasNext()) {
            beneficiosDisponibles.add(itDisponibles.next());
        }

        List<Object> todosBeneficios = new ArrayList<>();
        java.util.Iterator<?> itTodos = gestor.getSistemaRecompensas()
                .getBeneficiosPorNivel(u.getNivel())
                .iterator();

        while (itTodos.hasNext()) {
            todosBeneficios.add(itTodos.next());
        }

        model.addAttribute("usuario", u);
        model.addAttribute("beneficiosDisponibles", beneficiosDisponibles);
        model.addAttribute("todosBeneficios", todosBeneficios);

        return "beneficios";
    }

    @PostMapping("/beneficios/canjear")
    public String canjearBeneficio(@RequestParam String usuarioId,
            @RequestParam String beneficioId,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {

        Usuario u = gestor.getUsuario(usuarioId);

        if (u != null) {
            boolean exito = gestor.getSistemaRecompensas().canjearBeneficio(u, beneficioId);

            if (exito) {
                gestor.actualizarUsuario(u);
                gestor.generarAlerta(new Alerta(
                        "A" + System.currentTimeMillis(),
                        TipoAlerta.CANJE_BENEFICIO,
                        "Beneficio canjeado exitosamente",
                        usuarioId));
                attrs.addFlashAttribute("toast", "Beneficio canjeado exitosamente");
            } else {
                attrs.addFlashAttribute("toastError", "No tienes puntos suficientes para canjear este beneficio");
            }
        } else {
            attrs.addFlashAttribute("toastError", "No se encontró el usuario");
        }

        return "redirect:/beneficios/" + usuarioId;
    }

    @GetMapping("/rendimiento")
    public String rendimiento(Model model) {
        List<Usuario> usuarios = gestor.getTodosUsuarios();
        List<Transaccion> transacciones = gestor.getTodasTransacciones();

        long inicioLista = System.nanoTime();
        for (Usuario u : usuarios) {
            for (Usuario u2 : usuarios) {
                if (u2.getId().equals(u.getId())) {
                    break;
                }
            }
        }
        long tiempoLista = System.nanoTime() - inicioLista;

        long inicioHash = System.nanoTime();
        Map<String, Usuario> mapaUsuarios = new java.util.HashMap<>();
        for (Usuario u : usuarios) {
            mapaUsuarios.put(u.getId(), u);
        }
        for (Usuario u : usuarios) {
            mapaUsuarios.get(u.getId());
        }
        long tiempoHash = System.nanoTime() - inicioHash;

        long inicioBST = System.nanoTime();
        ArbolFidelizacion arbolTemp = new ArbolFidelizacion();
        for (Usuario u : usuarios) {
            arbolTemp.insertar(u);
        }
        arbolTemp.getOrdenadoPorPuntos();
        long tiempoBST = System.nanoTime() - inicioBST;

        long inicioListaSort = System.nanoTime();
        List<Usuario> listaTemp = new ArrayList<>(usuarios);
        listaTemp.sort((a, b) -> Integer.compare(a.getPuntosTotales(), b.getPuntosTotales()));
        long tiempoListaSort = System.nanoTime() - inicioListaSort;

        long inicioPila = System.nanoTime();
        PilaReversiones pilaTemp = new PilaReversiones();
        for (Transaccion t : transacciones) {
            pilaTemp.push(t);
        }
        while (!pilaTemp.estaVacia()) {
            pilaTemp.pop();
        }
        long tiempoPila = System.nanoTime() - inicioPila;

        long inicioCola = System.nanoTime();
        ColaNotificaciones colaTemp = new ColaNotificaciones();
        for (Transaccion t : transacciones) {
            colaTemp.encolar(new Alerta(
                    t.getId(),
                    TipoAlerta.SALDO_BAJO,
                    "test",
                    "u1"));
        }
        while (!colaTemp.estaVacia()) {
            colaTemp.despachar();
        }
        long tiempoCola = System.nanoTime() - inicioCola;

        model.addAttribute("tiempoLista", tiempoLista);
        model.addAttribute("tiempoHash", tiempoHash);
        model.addAttribute("tiempoBST", tiempoBST);
        model.addAttribute("tiempoListaSort", tiempoListaSort);
        model.addAttribute("tiempoPila", tiempoPila);
        model.addAttribute("tiempoCola", tiempoCola);
        model.addAttribute("totalUsuarios", usuarios.size());
        model.addAttribute("totalTransacciones", transacciones.size());

        return "rendimiento";
    }

    @PostMapping("/billetera/estado")
    public String cambiarEstado(@RequestParam String billeteraId,
            @RequestParam String estado,
            @RequestParam String usuarioId,
            org.springframework.web.servlet.mvc.support.RedirectAttributes attrs) {
        Billetera b = gestor.getBilletera(billeteraId);
        if (b != null) {
            b.setEstado(com.fintech.billetera.modelos.EstadoBilletera.valueOf(estado));
            gestor.registrarBilletera(b);
            attrs.addFlashAttribute("toast", "Estado de billetera actualizado a: " + estado);
        } else {
            attrs.addFlashAttribute("toastError", "No se encontró la billetera");
        }
        return "redirect:/usuarios/" + usuarioId;
    }
}
