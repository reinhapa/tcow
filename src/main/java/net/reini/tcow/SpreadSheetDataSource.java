package net.reini.tcow;

import java.math.BigDecimal;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;
import org.odftoolkit.odfdom.doc.table.OdfTable;
import org.odftoolkit.odfdom.doc.table.OdfTableCell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;

public final class SpreadSheetDataSource implements JRDataSource {
  private static final Integer UNKOWN = Integer.valueOf(-1);
  private static final Logger logger = LoggerFactory.getLogger(SpreadSheetDataSource.class);

  private final OdfTable table;
  private final Map<String, Integer> columnNameMap;

  private int rowIndex;

  public SpreadSheetDataSource(OdfSpreadsheetDocument odfSpreadsheetDocument) {
    table = odfSpreadsheetDocument.getTableList().get(0);
    columnNameMap = new HashMap<>();
    for (int x = 0, mx = table.getColumnCount(); x < mx; x++) {
      String keyName = table.getCellByPosition(x, 0).getStringValue();
      if (keyName.isEmpty()) {
        break;
      }
      columnNameMap.put(keyName, Integer.valueOf(x));
    }
    rowIndex = 0;
  }


  @Override
  public boolean next() throws JRException {
    return ++rowIndex < table.getRowCount()
        && !table.getCellByPosition(0, rowIndex).getStringValue().isEmpty();
  }

  @Override
  public Object getFieldValue(JRField jrField) throws JRException {
    final String name = jrField.getName();
    int columnIndex = columnNameMap.getOrDefault(name, UNKOWN).intValue();
    if (columnIndex < 0) {
      logger.warn("Unkown column name: {}", name);
      return null;
    }
    OdfTableCell cell = table.getCellByPosition(columnIndex, rowIndex);
    String valueType = cell.getValueType();
    if (valueType == null) {
      return null;
    }
    Class<?> valueClass = jrField.getValueClass();
    switch (valueType) {
      case "boolean":
        return convertValue(name, valueClass, cell.getBooleanValue());
      case "currency":
        return convertValue(name, valueClass, cell.getCurrencyValue());
      case "date":
        return convertValue(name, valueClass, cell.getDateValue());
      case "float":
        return convertValue(name, valueClass, cell.getDoubleValue());
      case "percentage":
        return convertValue(name, valueClass, cell.getPercentageValue());
      case "string":
        return convertValue(name, valueClass, cell.getStringValue());
      case "time":
        return convertValue(name, valueClass, cell.getTimeValue());
      default:
        return null;
    }
  }


  private Object convertValue(String name, Class<?> valueClass, Object value) {
    if (value != null) {
      if (valueClass.isInstance(value)) {
        return valueClass.cast(value);
      } else if (String.class.equals(valueClass)) {
        if (value instanceof String) {
          return value;
        } else if (value instanceof Boolean) {
          return String.valueOf(((Boolean) value).booleanValue());
        } else if (value instanceof Double) {
          return String.valueOf((((Double) value).doubleValue()));
        } else if (value instanceof Calendar) {
          return ((Calendar) value).toString();
        } else {
          return value.toString();
        }
      } else if (Integer.class.equals(valueClass)) {
        if (value instanceof String) {
          return Integer.valueOf((String) value);
        } else if (value instanceof Boolean) {
          return ((Boolean) value).booleanValue() ? Integer.valueOf(1) : Integer.valueOf(0);
        } else if (value instanceof Double) {
          return Integer.valueOf(((Double) value).intValue());
        } else if (value instanceof Calendar) {
          return Integer.valueOf((int) ((Calendar) value).getTimeInMillis());
        } else {
          return Integer.valueOf(value.toString());
        }
      } else if (BigDecimal.class.equals(valueClass)) {
        if (value instanceof String) {
          return new BigDecimal((String) value);
        } else if (value instanceof Boolean) {
          return ((Boolean) value).booleanValue() ? new BigDecimal(1) : new BigDecimal(0);
        } else if (value instanceof Double) {
          return new BigDecimal(((Double) value).doubleValue());
        } else if (value instanceof Calendar) {
          return new BigDecimal(((Calendar) value).getTimeInMillis());
        } else {
          return new BigDecimal(value.toString());
        }
      }
      logger.warn("Unkown to handle column name {} for value class {}", name, valueClass.getName());
    }
    return null;
  }

}
