# Evaluacion de Integracion de Sistemas - Apache Camel

## 1. Descripcion general

Este repositorio contiene una evaluacion practica de integracion usando Apache Camel.
El objetivo es implementar un flujo de File Transfer para procesar archivos CSV de pre-registros,
aplicar validaciones de calidad de datos y enrutar los archivos segun su resultado.

El proyecto fue estructurado para demostrar:

- Enrutamiento con Camel (input, output, archive, error)
- Validacion de contenido CSV con reglas de negocio
- Trazabilidad mediante logs en consola y archivo
- Ejecucion portable con Maven

## 2. Objetivo funcional

Automatizar la recepcion y validacion de archivos CSV de entrada, de modo que:

- Los archivos validos se entreguen a salida operativa y se archive una copia historica
- Los archivos invalidos se envien a una carpeta de cuarentena
- Todo el proceso quede registrado en logs

## 3. Flujo de integracion implementado

La ruta principal esta en src/main/java/FileTransferRoute.java y funciona asi:

1. Monitorea la carpeta input cada 5 segundos
2. Toma solo archivos .csv
3. Convierte el contenido a String
4. Ejecuta el procesador de validacion CSV
5. Si es valido:
	 - Copia el archivo a output
	 - Renombra para historial con timestamp y lo envia a archive
	 - Registra en log el nombre original eliminado de input
6. Si es invalido:
	 - Registra el motivo de rechazo
	 - Mueve el archivo a error

Adicionalmente hay manejo global de excepciones para evitar perdida de archivos en fallos no controlados.

## 4. Reglas de validacion CSV

El procesador CsvValidatorProcessor aplica estas reglas:

- V1: Encabezado obligatorio con columnas:
	patient_id, full_name, appointment_date, insurance_code
- V2: Ningun campo vacio en filas de datos
- V3: appointment_date con formato YYYY-MM-DD
- V4: insurance_code permitido: IESS, PRIVADO, NINGUNO

Headers generados durante la validacion:

- csv_valido: true o false
- csv_error: descripcion del primer error encontrado
- csv_filas: cantidad de filas procesadas (si valido)

## 5. Estructura principal del repositorio

- src/main/java/
	Codigo de la ruta Camel y procesador de validacion
- src/main/resources/
	Configuracion de logging (log4j2.xml)
- input/
	Carpeta de entrada monitoreada por Camel
- output/
	Archivos validos para consumo operativo
- archive/
	Copia historica renombrada con timestamp
- error/
	Archivos rechazados o fallidos
- logs/
	Salida de logs en archivo
- pom.xml
	Dependencias y configuracion de build/ejecucion con Maven

Nota: tambien se incluye una carpeta grande con codigo fuente de Apache Camel para referencia academica.

## 6. Tecnologias utilizadas

- Java 17
- Apache Camel 4.8.5
- Maven
- Log4j2

## 7. Ejecucion del proyecto

Desde la raiz del repositorio:

1. Compilar

	 mvn -DskipTests compile

2. Ejecutar

	 mvn exec:java

## 8. Logging

El proyecto usa log4j2.xml para registrar eventos en:

- Consola
- logs/integracion.log

La configuracion incluye rotacion diaria para mantener historial de ejecuciones.

## 9. Resultado esperado de una corrida

- Archivos validos terminan en output y archive
- Archivos invalidos terminan en error
- logs/integracion.log contiene trazas de inicio, validacion, decision y fin del proceso

## 10. Alcance academico

Este repositorio evidencia una solucion de integracion basada en patrones EIP sencillos
con foco en calidad de datos, trazabilidad y operacion reproducible.
