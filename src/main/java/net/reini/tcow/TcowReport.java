package net.reini.tcow;

import static java.nio.file.Files.exists;
import static java.nio.file.Files.newInputStream;

import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;

import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;

import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;

public class TcowReport {
  public static void main(String[] args) {
    if (args.length == 0) {
      System.out.println("Usage: TcowReport <datadirectory>");
    } else {
      try {
        final Path dataDir = Paths.get(args[0], "Rechnungsliste_Budget.ods");
        if (exists(dataDir)) {
          try (InputStream in = newInputStream(dataDir)) {
            JasperReport jasperReport = JasperCompileManager.compileReport("report.jrxml");
            JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, new HashMap<>(),
                new SpreadSheetDataSource(OdfSpreadsheetDocument.loadDocument(in)));
            JasperExportManager.exportReportToPdfFile(jasperPrint, "report.pdf");
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
  }
}
