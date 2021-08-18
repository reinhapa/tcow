package net.reini.tcow;

import java.util.HashMap;

import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;

import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;

public class TcowReport {
  public static void main(String[] args) {
    try {
      JasperReport jasperReport = JasperCompileManager.compileReport("report.jrxml");
      JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, new HashMap<>(),
          new SpreadSheetDataSource(OdfSpreadsheetDocument.loadDocument("/mnt/data/TCOW/2021/Rechnungsliste_Budget.ods")));
      JasperExportManager.exportReportToPdfFile(jasperPrint, "report.pdf");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
