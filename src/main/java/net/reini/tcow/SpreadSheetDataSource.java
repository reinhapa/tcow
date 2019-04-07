package net.reini.tcow;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.jopendocument.dom.spreadsheet.MutableCell;
import org.jopendocument.dom.spreadsheet.Sheet;
import org.jopendocument.dom.spreadsheet.SpreadSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;

public final class SpreadSheetDataSource implements JRDataSource {
  private static final Integer UNKOWN = Integer.valueOf(-1);
  private static final Logger logger = LoggerFactory.getLogger(SpreadSheetDataSource.class);

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
      logger.warn("Unkown column name: {}", name);
      return null;
    }
    MutableCell<SpreadSheet> cellValue = sheet.getCellAt(columnIndex, rowIndex);
    if (cellValue.isEmpty()) {
      return null;
    }
    Object value = cellValue.getValue();
    if (value != null) {
      Class<?> valueClass = jrField.getValueClass();
      if (valueClass.isInstance(value)) {
        return valueClass.cast(value);
      } else if (String.class.equals(valueClass)) {
        return cellValue.getTextValue();
      }
      if (value instanceof BigDecimal) {
        BigDecimal decimalValue = (BigDecimal) value;
        if (Integer.class.equals(valueClass)) {
          return Integer.valueOf(decimalValue.intValue());
        }
      }
      logger.warn("Unkown to handle column name {} for value class {}", name, valueClass.getName());
    }
    return null;
  }

}
