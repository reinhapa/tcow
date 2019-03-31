package net.reini.tcow;

import java.util.HashMap;
import java.util.Map;

import org.jopendocument.dom.spreadsheet.MutableCell;
import org.jopendocument.dom.spreadsheet.Sheet;
import org.jopendocument.dom.spreadsheet.SpreadSheet;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;

public final class SpreadSheetDataSource implements JRDataSource {
  private static final Integer UNKOWN = Integer.valueOf(-1);

  private final Sheet sheet;
  private final Map<String, Integer> columnNameMap;

  private int rowIndex;

  public SpreadSheetDataSource(SpreadSheet spreadSheet) {
    sheet = spreadSheet.getSheet(0);
    columnNameMap = new HashMap<>();
    for (int x = 0, mx = sheet.getColumnCount(); x < mx; x++) {
      String keyName = sheet.getCellAt(x, 0).getTextValue();
      if (keyName.isEmpty()) {
        break;
      }
      columnNameMap.put(keyName, Integer.valueOf(x));
    }
    rowIndex = 0;
  }


  @Override
  public boolean next() throws JRException {
    return ++rowIndex < sheet.getRowCount()
        && !sheet.getCellAt(0, rowIndex).getTextValue().isEmpty();
  }

  @Override
  public Object getFieldValue(JRField jrField) throws JRException {
    final String name = jrField.getName();
    int columnIndex = columnNameMap.getOrDefault(name, UNKOWN).intValue();
    if (columnIndex < 0) {
      System.out.println("Unkown column name: " + name);
      return null;
    }
    MutableCell<SpreadSheet> cellValue = sheet.getCellAt(columnIndex, rowIndex);
    switch (jrField.getValueClassName()) {
      case "java.lang.String":
        return cellValue.getTextValue();
      case "java.lang.Integer":
        return Integer.valueOf(cellValue.getTextValue());
      default:
        return null;
    }
  }

}
