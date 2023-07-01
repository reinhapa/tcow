package net.reini.tcow;

import static java.lang.String.format;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.time.format.DateTimeFormatter.ofPattern;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;
import org.odftoolkit.odfdom.doc.table.OdfTable;
import org.odftoolkit.odfdom.doc.table.OdfTableCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lowagie.text.Document;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfImportedPage;
import com.lowagie.text.pdf.PdfReader;

import ch.codeblock.qrinvoice.FontFamily;
import ch.codeblock.qrinvoice.OutputFormat;
import ch.codeblock.qrinvoice.PageSize;
import ch.codeblock.qrinvoice.QrInvoicePaymentPartReceiptCreator;
import ch.codeblock.qrinvoice.model.QrInvoice;
import ch.codeblock.qrinvoice.model.ReferenceType;
import ch.codeblock.qrinvoice.model.builder.QrInvoiceBuilder;
import ch.codeblock.qrinvoice.output.PaymentPartReceipt;
import ch.codeblock.qrinvoice.util.CreditorReferenceUtils;
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMessage.RecipientType;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;

public class Billing implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(Billing.class);
  private static final String RECHNUNG = "Rechnung";

  private final LocalDateTime date;
  private final Session mailSession;
  private final DateTimeFormatter dateFormatter;
  private final DateTimeFormatter yearFormatter;
  private final Path dataDir;
  private final InternetAddress sender;
  private final Pattern replacementPattern;
  private final Properties mailProperties;
  private final Properties creditorProperties;

  private Document pdfConcatenated;
  private PdfCopy pdfCopy;

  public Billing(Path dataDir) throws IOException {
    this.dataDir = dataDir;
    date = LocalDateTime.now();
    dateFormatter = ofPattern("dd.MM.yyyy", Locale.GERMAN);
    yearFormatter = ofPattern("yyyy", Locale.GERMAN);
    replacementPattern = Pattern.compile("\\$\\{([\\w]+)\\}");
    creditorProperties = loadProperties(Paths.get("creditor.properties"));
    mailProperties = loadProperties(Paths.get("mail.properties"));
    sender = new InternetAddress(creditorProperties.getProperty("senderAddress"),
        creditorProperties.getProperty("senderName"));
    mailSession = Session.getDefaultInstance(mailProperties);
  }

  public static void main(String[] args) {
    final int argsLength = args.length;
    if (argsLength > 0) {
      final Path dataDir = Paths.get(args[0]);
      try (Billing billing = new Billing(dataDir)) {
        if (argsLength > 1) {
          final Map<String, Object> row = new HashMap<>();
          row.put("Bezahlt", "");
          row.put("Mailed", "");
          row.put("Email", args[1]);
          for (int idx = 2; idx < argsLength; idx++) {
            String[] parts = args[idx].split("=", 2);
            row.put(parts[0], parts[1]);
          }
          billing.processDocument(row);
        } else {
          billing.readBillingInformation(dataDir.resolve("Rechnungsliste_Budget.ods"),
              billing::processDocument);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    } else {
      System.out.println("Usage: TCOW <datadirectory> [<targetEmail> [<rowkey=rowvalue> ..]]");
    }
  }

  static Properties loadProperties(Path propertiesFile) {
    final Properties properties = new Properties();
    if (exists(propertiesFile)) {
      try (InputStream in = newInputStream(propertiesFile)) {
        properties.load(in);
      } catch (IOException e) {
        LOGGER.info("{} failed to read. Using defaults.", propertiesFile, e);
      }
    } else {
      LOGGER.info("{} not available. Using defaults.", propertiesFile);
    }
    return properties;
  }

  @Override
  public void close() throws Exception {
    if (pdfConcatenated != null) {
      pdfConcatenated.close();
    }
    if (pdfCopy != null) {
      pdfCopy.close();
    }
  }

  Map<String, Object> processDocument(Map<String, Object> row) {
    try {
      Path pdfFile = createDirectories(dataDir.resolve(RECHNUNG + "en"))
          .resolve(format("%s_%s_%s.pdf", RECHNUNG, get("Vorname", row), get("Name", row))
              .replace(' ', '_'));
      if (!exists(pdfFile)) {
        row.put("QrInvoice", Base64.getEncoder().encodeToString(createQrInvoice(row)));
        createPdf(pdfFile, row);
      }
      String email = ""; // get("Email", row);
      if (email.isEmpty() || !"x".equalsIgnoreCase(get("RM", row))) {
        addToPrint(pdfFile);
      } else if (get("Mailed", row).isEmpty()) {
        return sendEmail(row, pdfFile);
      }
    } catch (Exception e) {
      LOGGER.error("Error processing PDF", e);
    }
    return Collections.emptyMap();
  }

  void addToPrint(Path pdfFile) throws IOException {
    try (InputStream in = newInputStream(pdfFile); PdfReader pdfReader = new PdfReader(in)) {
      if (pdfConcatenated == null) {
        pdfConcatenated = new Document();
      }
      if (pdfCopy == null) {
        pdfCopy = new PdfCopy(pdfConcatenated,
            newOutputStream(dataDir.resolve(RECHNUNG + "enToPrint.pdf")));
      }
      pdfConcatenated.open();
      for (int pagetIndex = 1,
          totalPages = pdfReader.getNumberOfPages(); pagetIndex <= totalPages; pagetIndex++) {
        // grab page from input document
        PdfImportedPage page = pdfCopy.getImportedPage(pdfReader, pagetIndex);
        // add content to target PDF
        pdfCopy.addPage(page);
      }
      pdfCopy.freeReader(pdfReader);
    }
  }

  void createPdf(Path pdfFile, Map<String, Object> row) throws IOException, JRException {
    LOGGER.info("Creating PDF {}", pdfFile);
    try (OutputStream pdfOut = newOutputStream(pdfFile)) {
      JasperReport jasperReport = JasperCompileManager.compileReport("report.jrxml");
      HashMap<String, Object> parameters = new HashMap<>();
      JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, parameters,
          new SingleRowDataSource((key, valueType) -> getTypeValue(row, key, valueType)));
      JasperExportManager.exportReportToPdfStream(jasperPrint, pdfOut);
    }
  }

  byte[] createQrInvoice(Map<String, Object> row) {
    final QrInvoice qrInvoice = QrInvoiceBuilder.create() //
        .creditorIBAN(creditorProperties.getProperty("creditorIBAN", "CH5204835012345671000"))
        .paymentAmountInformation(p -> p.chf(new BigDecimal(get("Betrag", row)))) //
        .creditor(c -> c //
            .structuredAddress() //
            .name(creditorProperties.getProperty("name", "Max Muster & Söhne")) //
            .streetName(creditorProperties.getProperty("streetName", "Musterstrasse")) //
            .houseNumber(creditorProperties.getProperty("houseNumber", "123")) //
            .postalCode(creditorProperties.getProperty("postalCode", "8000")) //
            .city(creditorProperties.getProperty("city", "Seldwyla")) //
            .country(creditorProperties.getProperty("country", "CH")) //
        ) //
        .ultimateDebtor(d -> d //
            .structuredAddress() //
            .name(format("%s %s", get("Vorname", row), get("Name", row))) //
            .streetName(get("Strasse", row)) //
            .houseNumber(get("Nr", row)) //
            .postalCode(get("PLZ", row)) //
            .city(get("Ort", row)) //
            .country("CH") //
        ) //
        .paymentReference(r -> r //
            .referenceType(ReferenceType.CREDITOR_REFERENCE) //
            .reference(CreditorReferenceUtils.createCreditorReference(get("R#", row))))
        .build(); //
    final PaymentPartReceipt paymentPartReceipt = QrInvoicePaymentPartReceiptCreator //
        .create() //
        .qrInvoice(qrInvoice) //
        .outputFormat(OutputFormat.PNG) //
        .pageSize(PageSize.DIN_LANG) //
        .fontFamily(FontFamily.LIBERATION_SANS) // or HELVETICA, ARIAL
        .locale(Locale.GERMAN) //
        .createPaymentPartReceipt(); //
    return paymentPartReceipt.getData();
  }

  Object getTypeValue(Map<String, Object> row, String key, Class<?> valueType) {
    String value = get(key, row);
    if (String.class.equals(valueType)) {
      return value;
    } else if (value.isEmpty()) {
      return null;
    } else if (Integer.class.equals(valueType)) {
      return Integer.valueOf(value);
    } else if (BigDecimal.class.equals(valueType)) {
      return new BigDecimal(value);
    } else if (Date.class.equals(valueType)) {
      return Date.from(dateFormatter.parse(value, LocalDate::from)
          .atStartOfDay(ZoneId.systemDefault()).toInstant());
    } else if (InputStream.class.equals(valueType)) {
      return new ByteArrayInputStream(Base64.getDecoder().decode(value));
    }
    return null;
  }

  void readBillingInformation(Path rechnungsListeOds,
      Function<Map<String, Object>, Map<String, Object>> rowFunction) throws Exception {
    final AtomicBoolean hasChanged = new AtomicBoolean();
    OdfSpreadsheetDocument doc = null;
    try (InputStream in = newInputStream(rechnungsListeOds)) {
      doc = OdfSpreadsheetDocument.loadDocument(in);
      OdfTable table = doc.getTableByName("Rechnungsliste");
      List<String> keys = new ArrayList<>();
      final Map<String, Integer> columnNameMap = new HashMap<>();
      for (int x = 0, mx = table.getColumnCount(); x < mx; x++) {
        String keyName = table.getCellByPosition(x, 0).getStringValue();
        if (keyName.isEmpty()) {
          break;
        }
        keys.add(keyName);
        columnNameMap.put(keyName, Integer.valueOf(x));
      }
      for (int y = 1, n = table.getRowCount(); y < n; y++) {
        String anrede = table.getCellByPosition(0, y).getStringValue();
        if (anrede.isEmpty()) {
          break;
        }
        Map<String, Object> rowValues = new LinkedHashMap<>();
        rowValues.put(keys.get(0), anrede);
        for (int x = 1, mx = keys.size(); x < mx; x++) {
          rowValues.put(keys.get(x), table.getCellByPosition(x, y).getStringValue());
        }
        final int row = y;
        Map<String, Object> changedRows = rowFunction.apply(rowValues);
        changedRows.forEach((key, value) -> {
          Integer col = columnNameMap.get(key);
          if (col != null) {
            setCellValue(table.getCellByPosition(col.intValue(), row), value);
            hasChanged.set(true);
          }
        });
      }
    }
    if (doc != null && hasChanged.get()) {
      try (OutputStream out = newOutputStream(dataDir.resolve("Rechnungsliste_Budget_new.ods"))) {
        doc.save(out);
      }
    }
  }

  void setCellValue(OdfTableCell cell, Object value) {
    if (value instanceof Date) {
      Calendar cal = Calendar.getInstance();
      cal.setTime((Date) value);
      cell.setDateValue(cal);
    } else if (value instanceof String) {
      cell.setStringValue((String) value);
    } else {
      LOGGER.warn("Value type {} converted to string....", value.getClass().getName());
      cell.setStringValue(value.toString());
    }
  }

  Map<String, Object> sendEmail(Map<String, Object> row, Path pdfFile)
      throws MessagingException, IOException {
    String email = get("Email", row);
    String email2 = get("Email2", row);
    String vorname = get("Vorname", row);
    String nachname = get("Name", row);
    String emailBody = getEmailBody(row);

    InternetAddress recipient = new InternetAddress(email, format("%s %s", vorname, nachname));
    Multipart mp = new MimeMultipart();

    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setText(emailBody, "ISO-8859-1");

    mp.addBodyPart(htmlPart);

    // Part two is attachment
    MimeBodyPart attachment = new MimeBodyPart();
    DataSource source = new ByteArrayDataSource(readAllBytes(pdfFile), "application/pdf");
    attachment.setDataHandler(new DataHandler(source));
    attachment.setFileName(RECHNUNG + ".pdf");
    mp.addBodyPart(attachment);

    MimeMessage msg = new MimeMessage(mailSession);
    msg.setFrom(sender);
    msg.addRecipient(RecipientType.TO, recipient);
    if (!email2.isEmpty()) {
      msg.addRecipient(RecipientType.CC, new InternetAddress(email2));
    }
    msg.setSubject("TCOW Jahresbeitrag ".concat(get("Jahr", row)));
    msg.setContent(mp);
    LOGGER.info("Sending document to {}", recipient);
    Transport.send(msg, mailProperties.getProperty("mail.user"),
        mailProperties.getProperty("mail.password"));
    Instant instant = date.toLocalDate().atStartOfDay().atZone(ZoneId.of("UTC")).toInstant();
    return Collections.singletonMap("Mailed", Date.from(instant));
  }

  String getEmailBody(Map<String, Object> row) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(getEmailResource(row))) {
      return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
          .map(line -> mapReplacements(line, row)).collect(Collectors.joining("\n"));
    }
  }

  String getEmailResource(Map<String, Object> row) {
    if (get("Bezahlt", row).isEmpty()) {
      return "BillingEmailTemplate.txt";
    }
    return "BillPayedEmailTemplate.txt";
  }

  String mapReplacements(String input, Map<String, Object> row) {
    StringBuffer result = new StringBuffer();
    Matcher matcher = replacementPattern.matcher(input);
    while (matcher.find()) {
      matcher.appendReplacement(result, get(matcher.group(1), row));
    }
    return matcher.appendTail(result).toString();
  }

  String get(String key, Map<String, Object> row) {
    return String.valueOf(row.computeIfAbsent(key, k -> {
      switch (k) {
        case "Jahr":
          return date.format(yearFormatter);
        default:
          return "<" + key + ">";
      }
    }));
  }
}
