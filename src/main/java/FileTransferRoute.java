import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.main.Main;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class FileTransferRoute extends RouteBuilder {

    public static void main(String[] args) throws Exception {
        Main main = new Main();
        main.configure().addRoutesBuilder(new FileTransferRoute());
        main.run();
    }

    @Override
    public void configure() throws Exception {

        // ── Manejo global de excepciones no controladas ───────────────────────
        onException(Exception.class)
            .handled(true)
            .log(LoggingLevel.ERROR,
                "[ERROR INESPERADO] Archivo: ${header.CamelFileName} | Causa: ${exception.message}")
            .to("file:error")
            .log(LoggingLevel.ERROR,
                "[FIN] Archivo movido a /error por excepción: ${header.CamelFileName}");

        // ── Ruta principal de integración File Transfer ───────────────────────
        //
        //   delete=true      → elimina el original de /input tras procesarlo
        //   include=.*\\.csv → solo archivos .csv
        //   initialDelay=2000→ espera 2s antes del primer polling
        //   delay=5000       → revisa la carpeta cada 5 segundos
        // ─────────────────────────────────────────────────────────────────────
        from("file:input?delete=true&include=.*\\.csv&initialDelay=2000&delay=5000")

            // ── PUNTO 1: Detección ────────────────────────────────────────────
            .log(LoggingLevel.INFO,
                "[INICIO] Archivo detectado: ${header.CamelFileName} – Tamaño: ${header.CamelFileLength} bytes")

            .convertBodyTo(String.class)

            // ── PUNTO 2: Inicio de validación ─────────────────────────────────
            .log(LoggingLevel.INFO,
                "[VALIDACIÓN] Procesando archivo: ${header.CamelFileName}")

            // Ejecutar procesador: setea csv_valido, csv_error, csv_filas
            .process(new CsvValidatorProcessor())

            // ── PUNTO 3: Decisión de enrutamiento ─────────────────────────────
            .choice()

                // ── Rama: ARCHIVO VÁLIDO ──────────────────────────────────────
                .when(header("csv_valido").isEqualTo(true))

                    // PUNTO 5: Validación exitosa con conteo de filas
                    .log(LoggingLevel.INFO,
                        "[OK] Archivo válido: ${header.CamelFileName} – Filas procesadas: ${header.csv_filas}")

                    // PUNTO 6: Copia a /output
                    .log(LoggingLevel.INFO,
                        "[OUTPUT] Archivo enviado a Facturación: ${header.CamelFileName}")

                    // Guardar nombre original antes del renombrado para logs finales
                    .setHeader("originalFileName", header("CamelFileName"))

                    .to("file:output")

                    // Renombrar con timestamp para archivado histórico
                    .setHeader("CamelFileName",
                        simple("pre_registros_${date:now:yyyy-MM-dd_HHmmss}.csv"))

                    // PUNTO 7: Archivado con timestamp
                    .log(LoggingLevel.INFO,
                        "[ARCHIVE] Copia archivada como: ${header.CamelFileName}")

                    .to("file:archive")

                    // PUNTO 8: Confirmación de eliminación del original
                    .log(LoggingLevel.INFO,
                        "[FIN] Archivo original eliminado de input: ${header.originalFileName}")

                // ── Rama: ARCHIVO INVÁLIDO ────────────────────────────────────
                .otherwise()

                    // PUNTO 4a: Motivo específico del rechazo
                    .log(LoggingLevel.WARN,
                        "[RECHAZO] Archivo: ${header.CamelFileName} – Motivo: ${header.csv_error}")

                    // PUNTO 4b: Enrutamiento a cuarentena
                    .log(LoggingLevel.WARN,
                        "[ERROR] Archivo enviado a cuarentena: ${header.CamelFileName}")

                    .to("file:error")

                    // PUNTO 8: Confirmación de eliminación del original
                    .log(LoggingLevel.WARN,
                        "[FIN] Archivo original eliminado de input: ${header.CamelFileName}")

            .end();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Procesador de validación CSV
    //
    // Reglas:
    //   V1 – Encabezado: patient_id, full_name, appointment_date, insurance_code
    //   V2 – Sin campos vacíos en ninguna fila de datos
    //   V3 – appointment_date con formato YYYY-MM-DD
    //   V4 – insurance_code: IESS | PRIVADO | NINGUNO
    //
    // Headers resultantes:
    //   csv_valido (boolean) → resultado global de la validación
    //   csv_error  (String)  → motivo del primer error encontrado
    //   csv_filas  (int)     → número de filas de datos procesadas (si válido)
    // ══════════════════════════════════════════════════════════════════════════
    static class CsvValidatorProcessor implements Processor {

        private static final List<String> REQUIRED_HEADERS =
                Arrays.asList("patient_id", "full_name", "appointment_date", "insurance_code");

        private static final List<String> VALID_INSURANCE_CODES =
                Arrays.asList("IESS", "PRIVADO", "NINGUNO");

        private static final Pattern DATE_PATTERN =
                Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

        @Override
        public void process(Exchange exchange) throws Exception {
            String body = exchange.getIn().getBody(String.class);

            if (body == null || body.trim().isEmpty()) {
                marcarInvalido(exchange, "El archivo está vacío");
                return;
            }

            String[] lineas = body.split("\\r?\\n");

            // ── V1: Encabezado correcto ───────────────────────────────────────
            String[] rawHeaders = lineas[0].split(",");
            List<String> headers = new java.util.ArrayList<>();
            for (String h : rawHeaders) headers.add(h.trim());

            for (String requerido : REQUIRED_HEADERS) {
                if (!headers.contains(requerido)) {
                    marcarInvalido(exchange,
                        "Encabezado incorrecto: falta la columna '" + requerido + "'");
                    return;
                }
            }

            int idxFecha  = headers.indexOf("appointment_date");
            int idxSeguro = headers.indexOf("insurance_code");
            int filasProcessadas = 0;

            // ── V2, V3, V4: Filas de datos ────────────────────────────────────
            for (int i = 1; i < lineas.length; i++) {
                String linea = lineas[i].trim();
                if (linea.isEmpty()) continue;

                String[] campos = linea.split(",", -1);

                // V2: Sin campos vacíos
                for (int j = 0; j < campos.length; j++) {
                    if (campos[j].trim().isEmpty()) {
                        marcarInvalido(exchange,
                            "Campo vacío en fila " + i + ", columna " + (j + 1));
                        return;
                    }
                }

                // V3: Formato de fecha YYYY-MM-DD
                if (idxFecha >= 0 && idxFecha < campos.length) {
                    String fecha = campos[idxFecha].trim();
                    if (!DATE_PATTERN.matcher(fecha).matches()) {
                        marcarInvalido(exchange,
                            "Formato de fecha inválido en fila " + i
                            + ": '" + fecha + "' (se esperaba YYYY-MM-DD)");
                        return;
                    }
                }

                // V4: Código de seguro válido
                if (idxSeguro >= 0 && idxSeguro < campos.length) {
                    String seguro = campos[idxSeguro].trim();
                    if (!VALID_INSURANCE_CODES.contains(seguro)) {
                        marcarInvalido(exchange,
                            "insurance_code inválido en fila " + i
                            + ": '" + seguro + "' (aceptados: IESS, PRIVADO, NINGUNO)");
                        return;
                    }
                }

                filasProcessadas++;
            }

            // Todas las validaciones pasaron
            exchange.getIn().setHeader("csv_valido", true);
            exchange.getIn().setHeader("csv_error", "");
            exchange.getIn().setHeader("csv_filas", filasProcessadas);
        }

        private void marcarInvalido(Exchange exchange, String motivo) {
            exchange.getIn().setHeader("csv_valido", false);
            exchange.getIn().setHeader("csv_error", motivo);
            exchange.getIn().setHeader("csv_filas", 0);
        }
    }
}
