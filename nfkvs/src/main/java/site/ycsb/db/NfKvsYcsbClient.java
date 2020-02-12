package site.ycsb.db;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.ByteBuffer;

import site.ycsb.ByteArrayByteIterator;
import site.ycsb.ByteIterator;
import site.ycsb.DB;
import site.ycsb.Status;
import site.ycsb.workloads.CoreWorkload;
import org.micron.nfkvs.API;
import org.micron.nfkvs.NFKVSEOFException;
import org.micron.nfkvs.NFKVSGenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author jjacob
 */
public class NfKvsYcsbClient extends DB {
  private static final Logger LOGGER = LoggerFactory.getLogger(NfKvsYcsbClient.class);

  private static API nfAPI;
  private static int valBufSize = 4096; // default

  private static final AtomicInteger INIT_COUNT = new AtomicInteger(0);
  private static String defYCSBPfx = "user";
  private static int kvsPfxLen = 0;

  private static String getKvsPfx(String key) {
    if (kvsPfxLen == 0 || key == null) {
      return null;
    }

    /* Use non-prefixed cursor if key length is smaller/same as pfx. length. */
    if (key.length() <= kvsPfxLen) {
      return null;
    }

    return key.substring(0, kvsPfxLen);
  }

  private static String getNextPfx(String curPfx) {
    if (kvsPfxLen == 0 || curPfx == null) {
      return null;
    }

    if (!curPfx.contains(defYCSBPfx)) {
      LOGGER.error("Default YCSB prefix string, " + defYCSBPfx +
                   ", has changed.");
      System.exit(1);
    }

    int ycsbPfxLen = defYCSBPfx.length();
    int curPfxLen = curPfx.length();

    /* Ensured by earlier checks in init() and getKvsPfx(). */
    assert(curPfxLen > ycsbPfxLen && curPfxLen == kvsPfxLen);

    // Below logic is tightly coupled with the YCSB key format: "user[0-9]+"
    Long nextPfx = new Long(0);
    try {
      nextPfx = Long.parseLong(curPfx.substring(ycsbPfxLen, curPfxLen));
      ++nextPfx;
    } catch (NumberFormatException e) {
      // [MU_REVISIT]: This restriction can be removed if we binary add 1.
      LOGGER.error("Prefix " + curPfx + " contains non-numeric chars");
      System.exit(1);
    }

    Double maxPfx = Math.pow(10, curPfxLen - ycsbPfxLen) - 1;
    if (nextPfx > maxPfx) {
      /* No more prefixes to scan. */
      return null;
    }

    return defYCSBPfx + Long.toString(nextPfx);
  }

  private String getKvsPathParam(Properties props) {
    return props.getProperty("nfkvs.kvs_path");
  }

  private String getMpoolNameParam(Properties props) {
    String mpoolName = props.getProperty("nfkvs.mpool_name");
    if (mpoolName == null)   {
      mpoolName = props.getProperty("nfkvs_mpool_name");
    }

    return mpoolName;
  }

  private String getKvsNameParam(Properties props) {
    String kvsName = props.getProperty("nfkvs.kvs_name");
    if (kvsName == null)   {
      kvsName = props.getProperty("nfkvs_kvs_name");
    }

    return kvsName;
  }

  private String getKvdbRParamsParam(Properties props) {
    String kvdbParams = props.getProperty("nfkvs.kvdb_rparams");
    if (kvdbParams == null)   {
      kvdbParams = props.getProperty("kvdb_params");
    }

    if (kvdbParams != null) {
      kvdbParams = kvdbParams.replace(';', ',');
    }

    return kvdbParams;
  }

  private String getKvsRParamsParam(Properties props) {
    String kvsParams = props.getProperty("nfkvs.kvs_rparams");
    if (kvsParams == null)   {
      kvsParams = props.getProperty("kvs_params");
    }

    if (kvsParams != null) {
      kvsParams = kvsParams.replace(';', ',');
    }

    return kvsParams;
  }

  private String getPfxlenParam(Properties props) {
    String pfxlen = props.getProperty("nfkvs.pfxlen");
    if (pfxlen == null)   {
      pfxlen = props.getProperty("nfkvs_pfxlen");
    }

    return pfxlen;
  }

  /**
   * Initialize any state for this DB.
   * Called once per DB instance; there is one DB instance per client thread.
   */
  @Override
  public void init() {
    INIT_COUNT.incrementAndGet();

    synchronized (INIT_COUNT) {
      if (nfAPI == null) {
        nfAPI = new API();
        API.loadLibrary();

        Properties props = getProperties();

        String mpoolName = null;
        String kvsName = null;    // actually "<kvdb>/<kvs>"

        String kvsPath = getKvsPathParam(props);

        if (null != kvsPath) {
          String[] names = kvsPath.split("/");
          if (names.length != 3 ||
              names[0].length() < 1 || names[1].length() < 1 || names[2].length() < 1)
          {
            LOGGER.error("invalid kvs path [" + kvsPath + "]");
            System.exit(1);
          }

          mpoolName = names[0];
          kvsName = names[1] + "/" + names[2];
        } else {
          mpoolName = getMpoolNameParam(props);
          kvsName = getKvsNameParam(props);
        }

        if (null == mpoolName || null == kvsName) {
          LOGGER.error("nfkvs.kvs_path not configured");
          System.exit(1);
        }

        String kvdbParams = getKvdbRParamsParam(props);
        if (null == kvdbParams) {
          LOGGER.info("property nfkvs.kvdb_rparams not specified, using default configuration");
          kvdbParams = "";
        }

        String kvsParams = getKvsRParamsParam(props);
        if (null == kvsParams) {
          LOGGER.info("property nfkvs.kvs_rparams not specified, using default configuration");
          kvsParams = "";
        }

        String pfxlen = getPfxlenParam(props);
        if (pfxlen != null) {
          kvsPfxLen = Integer.parseInt(pfxlen);
        }

        if (kvsPfxLen > 0) {
          if (kvsPfxLen <= defYCSBPfx.length()) {
            LOGGER.error("KVS Prefix length must be greater than the length " +
                         "of the default YCSB prefix, " + defYCSBPfx);
            System.exit(1);
          }
        }

        final double scanProportion = Double.valueOf(props.getProperty(
            CoreWorkload.SCAN_PROPORTION_PROPERTY,
            CoreWorkload.SCAN_PROPORTION_PROPERTY_DEFAULT));
        if (scanProportion > 0) {
          /* Parameter for workloads with scans, like workload E. */
          if (!kvdbParams.isEmpty() && !kvdbParams.endsWith(",")) {
            kvdbParams += ",";
          }
          kvdbParams += "csched_scatter_pct=1";
        }

        String fieldCount = props.getProperty(
            CoreWorkload.FIELD_COUNT_PROPERTY,
            CoreWorkload.FIELD_COUNT_PROPERTY_DEFAULT
        );

        String fieldLength = props.getProperty(
            CoreWorkload.FIELD_LENGTH_PROPERTY,
            CoreWorkload.FIELD_LENGTH_PROPERTY_DEFAULT
        );

        int readFieldCount = Integer.parseInt(fieldCount);
        valBufSize = readFieldCount * (Integer.parseInt(fieldLength) + 20);

        LOGGER.info("kvdb_rparams=\"" + kvdbParams + "\"");
        LOGGER.info("kvs_rparams=\"" + kvsParams + "\"");

        try {
          nfAPI.init(valBufSize);
          nfAPI.open((short) 1, mpoolName, kvsName, kvdbParams, kvsParams,
                     10, "cfgArgv");
        } catch (NFKVSGenException e) {
          e.printStackTrace();
          LOGGER.error("Could not open NFKVS with mpool name [" + mpoolName + "] and kvs name ["
              + kvsName + "]");
          System.exit(1);
        }
      }
    }
  }

  /**
   * Cleanup any state for this DB.
   * Called once per DB instance; there is one DB instance per client thread.
   */
  @Override
  public void cleanup() {
    synchronized (INIT_COUNT) {
      // Close only for the last one
      if (INIT_COUNT.decrementAndGet() == 0) {
        try {
          nfAPI.close();
        } catch (NFKVSGenException e) {
          e.printStackTrace();
          LOGGER.warn("Could not close NFKVS");
        }
      }
    }
  }

  @Override
  public Status read(String table, String key, Set<String> fields, Map<String, ByteIterator> result) {
    try {
      byte[] keyA = key.getBytes();
      byte[] valA = nfAPI.get(keyA);
      // TODO how does API handle not found?
      deserializeValues(valA, fields, result);
      return Status.OK;
    } catch (NFKVSGenException e) {
      e.printStackTrace();
      return Status.ERROR;
    }
  }

  @Override
  public Status scan(String table, String startkey, int recordcount, Set<String> fields,
                     Vector<HashMap<String, ByteIterator>> result) {
    Status status = Status.OK;
    String curPfx = null;
    int curPfxLen = 0;

    // [MU_REVISIT]: Maybe cleaner to separate prefixed and non-prefixed paths.
    try {
      curPfx = getKvsPfx(startkey);
      curPfxLen = (curPfx != null) ? curPfx.length() : 0;
      nfAPI.createCursor(curPfx, curPfxLen);
    } catch (NFKVSGenException e) {
      e.printStackTrace();
      return Status.ERROR;
    }

    try {
      byte[] foundkey = nfAPI.seek(startkey.getBytes());
      if (foundkey == null || foundkey.length == 0) {
        LOGGER.error("Nothing found in scan for startkey=" + startkey);
        return Status.ERROR;
      }

      result.ensureCapacity(recordcount);

      int i = 0;
      int tries = 3;
      do {
        try {
          for (; i < recordcount; i++) {
            byte[] value = nfAPI.read();

            HashMap<String, ByteIterator> map = new HashMap<>();

            deserializeValues(value, fields, map);
            result.add(map);
          }
          break;
        } catch (NFKVSEOFException e) {
          curPfx = getNextPfx(curPfx);
          // Do nothing if this is a non-prefixed cursor or is the last prefix.
          if (curPfx == null) {
            break;
          }

          /* This loop handles the case where we are at the end of a prefix and
           * haven't scanned 'recordcount' records yet. The getNextPfx()
           * returns the next prefix in lexicographic order. If there are 3
           * successive empty prefixes, we fallback to a non-prefixed cursor
           * to avoid getting stuck in searching through a large sequence of
           * empty prefixes.
           */
          curPfxLen = curPfx.length();

          String tmpPfx = null;
          if (tries-- == 0) {
            // Fall back to a non-prefixed cursor after 3 attempts.
            tmpPfx = curPfx;
            curPfx = null;
            curPfxLen = 0;
          }

          nfAPI.destroyCursor();
          nfAPI.createCursor(curPfx, curPfxLen);

          if (tmpPfx != null) {
            foundkey = nfAPI.seek(tmpPfx.getBytes());
            if (foundkey == null || foundkey.length == 0) {
              // do nothing
              break;
            }
          }
        }
      } while(true);
    } catch (NFKVSGenException e) {
      e.printStackTrace();
      status = Status.ERROR;
    } finally {
      try {
        nfAPI.destroyCursor();
      } catch (NFKVSGenException e) {
        e.printStackTrace();
      }
    }

    return status;
  }

  @Override
  public Status update(String table, String key, Map<String, ByteIterator> values) {
    Status status = Status.OK;
    try {
      byte[] keyB = key.getBytes();
      byte[] valB = serializeValues(values);

      nfAPI.put(keyB, valB);
    } catch (IOException | NFKVSGenException e) {
      e.printStackTrace();
      status = Status.ERROR;
    }
    return status;
  }

  @Override
  public Status insert(String table, String key, Map<String, ByteIterator> values) {
    Status status = Status.OK;
    try {
      byte[] keyB = key.getBytes();
      byte[] valB = serializeValues(values);

      nfAPI.put(keyB, valB);
    } catch (IOException | NFKVSGenException e) {
      e.printStackTrace();
      status = Status.ERROR;
    }
    return status;
  }

  @Override
  public Status delete(String table, String key) {
    Status status = Status.OK;
    try {
      nfAPI.del(key.getBytes());
    } catch (NFKVSGenException e) {
      e.printStackTrace();
      status = Status.ERROR;
    }
    return status;
  }

  private Map<String, ByteIterator> deserializeValues(final byte[] values, final Set<String> fields,
      final Map<String, ByteIterator> result) {
    // Borrowed from site.ycsb.db.rocksdb.RocksDBClient
    final ByteBuffer buf = ByteBuffer.allocate(4);

    int offset = 0;
    while(offset < values.length) {
      buf.put(values, offset, 4);
      buf.flip();
      final int keyLen = buf.getInt();
      buf.clear();
      offset += 4;

      final String key = new String(values, offset, keyLen);
      offset += keyLen;

      buf.put(values, offset, 4);
      buf.flip();
      final int valueLen = buf.getInt();
      buf.clear();
      offset += 4;

      if(fields == null || fields.contains(key)) {
        result.put(key, new ByteArrayByteIterator(values, offset, valueLen));
      }

      offset += valueLen;
    }

    return result;
  }

  private byte[] serializeValues(final Map<String, ByteIterator> values) throws IOException {
    // Borrowed from site.ycsb.db.rocksdb.RocksDBClient
    try(final ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      final ByteBuffer buf = ByteBuffer.allocate(4);

      for(final Map.Entry<String, ByteIterator> value : values.entrySet()) {
        final byte[] keyBytes = value.getKey().getBytes(UTF_8);
        final byte[] valueBytes = value.getValue().toArray();

        buf.putInt(keyBytes.length);
        baos.write(buf.array());
        baos.write(keyBytes);

        buf.clear();

        buf.putInt(valueBytes.length);
        baos.write(buf.array());
        baos.write(valueBytes);

        buf.clear();
      }
      return baos.toByteArray();
    }
  }
}
