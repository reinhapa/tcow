package net.reini.tcow;

import static java.nio.file.Files.exists;
import static java.nio.file.Files.write;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.sf.jasperreports.engine.JRException;

class BillingTest {

  @TempDir
  Path dataDir;
  Billing billing;

  @BeforeEach
  void prepare() throws IOException {
    billing = new Billing(dataDir);
  }

  @AfterEach
  void cleanup() throws Exception {
    billing.close();
  }

  @Test
  void createPdfTyp2() throws IOException, JRException {
    Path pdfFile = dataDir.resolve("rechnung-typ2.pdf");
    Map<String, Object> row = rowData("2");

    row.put("QrInvoice", Base64.getEncoder().encodeToString(billing.createQrInvoice(row)));
    billing.createPdf(pdfFile, row);
    assertTrue(exists(pdfFile));
  }

  @Test
  void createPdfTyp3() throws IOException, JRException {
    Path pdfFile = dataDir.resolve("rechnung-typ3.pdf");
    Map<String, Object> row = rowData("3");

    row.put("QrInvoice", Base64.getEncoder().encodeToString(billing.createQrInvoice(row)));
    billing.createPdf(pdfFile, row);
    assertTrue(exists(pdfFile));
  }

  @Test
  void createQrInvoice() throws IOException {
    Path pdfFile = dataDir.resolve("QrInvoice.png");
    Map<String, Object> row = rowData("3");

    byte[] data = billing.createQrInvoice(row);
    write(pdfFile, data);
    assertTrue(exists(pdfFile));
  }

  private Map<String, Object> rowData(String typ) {
    Map<String, Object> row = new HashMap<>();
    row.put("Anrede", "Herr");
    row.put("Vorname", "Andreas");
    row.put("Name", "Müller");
    row.put("Strasse", "Heimatweg");
    row.put("Nr", "3");
    row.put("PLZ", "1234");
    row.put("Ort", "Dingsda");
    row.put("R#", "2020 42");
    row.put("Bezeichnung", "Beitrag");
    row.put("Betrag", "42.50");
    row.put("Typ", typ);
    row.put("Bezahlt", "");
    return row;
  }
}
