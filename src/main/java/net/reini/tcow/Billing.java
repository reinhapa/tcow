package net.reini.tcow;

import static com.sun.mail.smtp.SMTPMessage.NOTIFY_FAILURE;
import static com.sun.mail.smtp.SMTPMessage.RETURN_HDRS;
import static java.lang.String.format;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.time.format.DateTimeFormatter.ofPattern;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage.RecipientType;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.jopendocument.dom.spreadsheet.Sheet;
import org.jopendocument.dom.spreadsheet.SpreadSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.itextpdf.forms.PdfAcroForm;
import com.itextpdf.forms.PdfPageFormCopier;
import com.itextpdf.forms.fields.PdfFormField;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.layout.Canvas;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.property.TextAlignment;
import com.itextpdf.layout.property.VerticalAlignment;
import com.sun.mail.smtp.SMTPMessage;

public class Billing implements AutoCloseable {
  private static final String RECHNUNG = "Rechnung";

  public static void main(String[] args) {
    int argsLength = args.length;
    if (argsLength > 0) {
      try (Billing billing = new Billing(args[0])) {
        if (argsLength > 1) {
          Map<String, String> row = new HashMap<>();
          row.put("Bezahlt", "");
          row.put("Mailed", "");
          row.put("Email", args[1]);
          for (int idx = 2; idx < argsLength; idx++) {
            String[] parts = args[idx].split("=", 2);
            row.put(parts[0], parts[1]);
          }
          billing.processDocument(row);
        } else {
          billing.readBillingInformation(billing::processDocument);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    } else {
      System.out.println("Usage: TCOW <datadirectory> [<targetEmail> [<rowkey=rowvalue> ..]]");
    }
  }

  private final Logger logger;
  private final LocalDateTime date;
  private final Session mailSession;
  private final DateTimeFormatter dateFormatter;
  private final DateTimeFormatter yearFormatter;
  private final Path dataDir;
  private final InternetAddress sender;
  private final Pattern replacementPattern;
  private final Properties mailProperties;
  private final PdfPageFormCopier formCopier;
  private PdfDocument pdfConcatenated;

  public Billing(String dataDirectory) throws IOException {
    dataDir = Paths.get(dataDirectory);
    logger = LoggerFactory.getLogger(getClass());
    date = LocalDateTime.now();
    dateFormatter = ofPattern("dd. MMMM yyyy", Locale.GERMAN);
    yearFormatter = ofPattern("yyyy", Locale.GERMAN);
    sender = new InternetAddress("patrick@reini.net", "Patrick Reinhart");
    replacementPattern = Pattern.compile("\\$\\{([\\w]+)\\}");
    mailProperties = new Properties();
    formCopier = new PdfPageFormCopier();
    Path mailPropertiesFile = Paths.get("mail.properties");
    if (exists(mailPropertiesFile)) {
      try (InputStream in = newInputStream(mailPropertiesFile)) {
        mailProperties.load(in);
      }
    } else {
      logger.info("{} not available. Using defaults.", mailPropertiesFile);
    }
    mailSession = Session.getDefaultInstance(mailProperties);
  }

  @Override
  public void close() throws Exception {
    if (pdfConcatenated != null) {
      pdfConcatenated.close();
    }
  }

  Map<String, Object> processDocument(Map<String, String> row) {
    String vorname = get("Vorname", row);
    String nachname = get("Name", row);
    String anrede = get("Anrede", row);
    String strasse = get("Strasse", row);
    String plz = get("PLZ", row);
    String ort = get("Ort", row);
    String bezeichnung = get("Bezeichnung", row);
    String betrag = get("Betrag", row);
    String email = get("Email", row);
    String typ = get("Typ", row);
    String vornameEinzeln = vorname.replaceFirst(" & .+", "").concat(",");
    String postAdresse =
        format("%s\n%s %s\n%s\n%s %s", anrede, vorname, nachname, strasse, plz, ort);
    String bezahltAm = get("Bezahlt", row);
    try {
      Path pdfFile = createDirectories(dataDir.resolve(RECHNUNG + "en"))
          .resolve(format("%s_%s_%s.pdf", RECHNUNG, vorname, nachname).replace(' ', '_'));
      if (!exists(pdfFile)) {
        PdfReader pfdReader = createReader(typ);
        logger.info("Creating PDF {}", pdfFile);
        try (PdfDocument pdfDocument = new PdfDocument(pfdReader, new PdfWriter(newOutputStream(pdfFile)))){
          PdfAcroForm form = PdfAcroForm.getAcroForm(pdfDocument, true);
          Map<String, PdfFormField> fields = form.getFormFields();
          fields.getOrDefault("Adresse", PdfFormField.createEmptyField(pdfDocument)).setValue(postAdresse);
          fields.getOrDefault("Vorname", PdfFormField.createEmptyField(pdfDocument)).setValue(vornameEinzeln);
          fields.getOrDefault("Jahr1", PdfFormField.createEmptyField(pdfDocument)).setValue(date.format(yearFormatter));
          fields.getOrDefault("Jahr2", PdfFormField.createEmptyField(pdfDocument)).setValue(date.format(yearFormatter));
          fields.getOrDefault("Datum", PdfFormField.createEmptyField(pdfDocument)).setValue(date.format(dateFormatter));
          fields.getOrDefault("Bezeichnung", PdfFormField.createEmptyField(pdfDocument)).setValue(bezeichnung);
          fields.getOrDefault("Betrag", PdfFormField.createEmptyField(pdfDocument)).setValue(betrag);
          
          //Flatten fields
          form.flattenFields();
          
          if (!bezahltAm.isEmpty()) {
            logger.info("{} {} already payed on {}", vorname, nachname, bezahltAm);
            Paragraph phrase = new Paragraph(format("Betrag erhalten am %s", bezahltAm));
            phrase.setFontColor(DeviceRgb.BLUE);
            PdfCanvas over = new PdfCanvas(pdfDocument.getFirstPage());
            try (Canvas canvas = new Canvas(over, pdfDocument, pdfDocument.getDefaultPageSize())) {
              canvas.showTextAligned(phrase, 380, 50, 1, TextAlignment.CENTER, VerticalAlignment.TOP, 0.1f);
            }
          }
        }
      }
      if (email.isEmpty()) {
        try (InputStream in = newInputStream(pdfFile)) {
          if (pdfConcatenated == null) {
            pdfConcatenated =
                new PdfDocument(new PdfWriter(newOutputStream(dataDir.resolve(RECHNUNG + "enToPrint.pdf"))));
          }
          try (PdfDocument pdfDocument = new PdfDocument(new PdfReader(in))) {
            pdfDocument.copyPagesTo(1, pdfDocument.getNumberOfPages(), pdfConcatenated, formCopier);
          }
        }
      } else if (get("Mailed", row).isEmpty()) {
         return sendEmail(row, pdfFile);
      }
    } catch (Exception e) {
      logger.error("Error processing PDF", e);
    }
    return Collections.emptyMap();
  }

  PdfReader createReader(String typ) throws IOException {
    switch (typ) {
      default:
        return new PdfReader(getClass().getResourceAsStream("Mitglied.pdf"));
      case "3":
        return new PdfReader(getClass().getResourceAsStream("LuftAbo.pdf"));
    }
  }

  void readBillingInformation(Function<Map<String, String>, Map<String, Object>> rowFunction)
      throws Exception {
    final AtomicBoolean hasChanged = new AtomicBoolean();
    SpreadSheet spreadSheet =
        SpreadSheet.createFromFile(dataDir.resolve("Rechnungsliste_Budget.ods").toFile());
    try {
      Sheet sheet = spreadSheet.getSheet(0);
      List<String> keys = new ArrayList<>();
      final Map<String, Integer> columnNameMap = new HashMap<>();
      for (int x = 0, mx = sheet.getColumnCount(); x < mx; x++) {
        String keyName = sheet.getCellAt(x, 0).getTextValue();
        if (keyName.isEmpty()) {
          break;
        }
        keys.add(keyName);
        columnNameMap.put(keyName, Integer.valueOf(x));
      }
      for (int y = 1, n = sheet.getRowCount(); y < n; y++) {
        String anrede = sheet.getCellAt(0, y).getTextValue();
        if (anrede.isEmpty()) {
          break;
        }
        Map<String, String> rowValues = new LinkedHashMap<>();
        rowValues.put(keys.get(0), anrede);
        for (int x = 1, mx = keys.size(); x < mx; x++) {
          rowValues.put(keys.get(x), sheet.getCellAt(x, y).getTextValue());
        }
        final int row = y;
        Map<String, Object> changedRows = rowFunction.apply(rowValues);
        changedRows.forEach((key, value) -> {
          Integer col = columnNameMap.get(key);
          if (col != null) {
            sheet.setValueAt(value, col.intValue(), row);
            hasChanged.set(true);
          }
        });
      }
    } finally {
      if (hasChanged.get()) {
        spreadSheet.saveAs(dataDir.resolve("Rechnungsliste_Budget_new.ods").toFile());
      }
    }
  }

  Map<String, Object> sendEmail(Map<String, String> row, Path pdfFile)
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

    SMTPMessage msg = new SMTPMessage(mailSession);
    msg.setReturnOption(RETURN_HDRS);
    msg.setNotifyOptions(NOTIFY_FAILURE);
    msg.setSender(sender);
    msg.setFrom(sender);
    msg.addRecipient(RecipientType.TO, recipient);
    if (!email2.isEmpty()) {
      msg.addRecipient(RecipientType.CC, new InternetAddress(email2));
    }
    msg.setSubject("TCOW Jahresbeitrag ".concat(get("Jahr1", row)));
    msg.setContent(mp);
    logger.info("Sending document to {}", recipient);
    Transport.send(msg, mailProperties.getProperty("mail.user"),
        mailProperties.getProperty("mail.password"));
    Instant instant = date.toLocalDate().atStartOfDay().atZone(ZoneId.of("UTC")).toInstant();
    return Collections.singletonMap("Mailed", Date.from(instant));
  }

  String getEmailBody(Map<String, String> row) throws IOException {
    try (InputStream in = getClass().getResourceAsStream(getEmailResource(row))) {
      return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
          .map(line -> mapReplacements(line, row)).collect(Collectors.joining("\n"));
    }
  }

  String getEmailResource(Map<String, String> row) {
    if (get("Bezahlt", row).isEmpty()) {
      return "BillingEmailTemplate.txt";
    }
    return "BillPayedEmailTemplate.txt";
  }

  String mapReplacements(String input, Map<String, String> row) {
    StringBuffer result = new StringBuffer();
    Matcher matcher = replacementPattern.matcher(input);
    while (matcher.find()) {
      matcher.appendReplacement(result, get(matcher.group(1), row));
    }
    return matcher.appendTail(result).toString();
  }

  String get(String key, Map<String, String> row) {
    return row.computeIfAbsent(key, k -> {
      switch (k) {
        case "Jahr1":
        case "Jahr2":
          return date.format(yearFormatter);
        default:
          return "<" + key + ">";
      }
    });
  }
}
