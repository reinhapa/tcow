import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SampleTest {

  @Test
  public void test() throws Exception {
    Logger logger = LoggerFactory.getLogger(getClass());
    
    logger.info("gugus");
  }

}
