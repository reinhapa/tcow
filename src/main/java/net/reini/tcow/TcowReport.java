package net.reini.tcow;

import java.io.File;
import java.util.HashMap;

import org.jopendocument.dom.spreadsheet.SpreadSheet;

import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;

public class TcowReport {
  public static void main(String[] args) {
    // try (Connection con =
    // DriverManager.getConnection("jdbc:mysql://svtapir.home/reini", "pr", "mirexal")) {
    try {
      JasperReport jasperReport = JasperCompileManager.compileReport("Mitglied.jrxml");
      JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, new HashMap<>(),
          new SpreadSheetDataSource(SpreadSheet
              .createFromFile(new File("/mnt/Data/TCOW/2019/Rechnungsliste_Budget.ods"))));
      JasperExportManager.exportReportToPdfFile(jasperPrint, "Mitglied.pdf");
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
