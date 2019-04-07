package net.reini.tcow;

import java.util.function.BiFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRField;

public final class SingleRowDataSource implements JRDataSource {
  private static final Logger logger = LoggerFactory.getLogger(SingleRowDataSource.class);

  private final BiFunction<String, Class<?>, Object> rowData;

  private boolean atEnd;

  public SingleRowDataSource(BiFunction<String, Class<?>, Object> rowData) {
    this.rowData = rowData;
  }

  @Override
  public boolean next() throws JRException {
    if (!atEnd) {
      atEnd = true;
      return true;
    }
    return false;
  }

  @Override
  public Object getFieldValue(JRField jrField) throws JRException {
    final String name = jrField.getName();
    final Class<?> valueClass = jrField.getValueClass();
    final Object value = rowData.apply(name, valueClass);
    if (value != null) {
      if (valueClass.isInstance(value)) {
        return valueClass.cast(value);
      } else {
        logger.warn("Unkown to handle column name {} for value class {}", name, valueClass.getName());
      }
    }
    return null;
  }

}
