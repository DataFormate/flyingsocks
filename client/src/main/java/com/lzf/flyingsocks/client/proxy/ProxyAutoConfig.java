package com.lzf.flyingsocks.client.proxy;

import com.lzf.flyingsocks.AbstractConfig;
import com.lzf.flyingsocks.Config;
import com.lzf.flyingsocks.ConfigInitializationException;
import com.lzf.flyingsocks.ConfigManager;
import com.lzf.flyingsocks.client.GlobalConfig;
import com.lzf.flyingsocks.util.BaseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 代理模式相关组件
 */
public class ProxyAutoConfig extends AbstractConfig implements Config {
    public static final String DEFAULT_NAME = "Config-PAC";

    private static final Logger log = LoggerFactory.getLogger(ProxyAutoConfig.class);

    private static final Charset DEFAULT_CONFIG_ENCODING = StandardCharsets.UTF_8;

    private static final String PAC_CONFIG_FILE = "pac-setting";
    private static final String GFWLIST_FILE = "pac.txt";
    private static final String CNIPV4_FILE = "cnipv4.txt";

    public static final int PROXY_NO = 0;
    public static final int PROXY_GFW_LIST = 1;
    public static final int PROXY_GLOBAL = 2;
    public static final int PROXY_NON_CN = 3;

    /**
     * 系统代理模式
     */
    private volatile int proxyMode;

    /**
     * 需要代理的域名/IP列表,仅PAC模式使用,按照其域名的逆向字符串存储,方便按照前缀查询
     */
    private volatile NavigableSet<String> proxySet = Collections.emptyNavigableSet();

    /**
     * 中国IP地址Map,键为32位的IPV4地址,值为32位的掩码
     */
    private volatile NavigableMap<Integer, Integer> whiteListMap = Collections.emptyNavigableMap();


    ProxyAutoConfig(ConfigManager<?> configManager) {
        super(configManager, DEFAULT_NAME);
    }

    @Override
    protected void initInternal() throws ConfigInitializationException {
        configManager.setSystemProperties("sun.net.spi.nameservice.provider.1", "dns,sun");//解决部分情况IPV6解析失败的场景
        GlobalConfig cfg = configManager.getConfig(GlobalConfig.NAME, GlobalConfig.class);
        File gfwFile = new File(cfg.configPath(), GFWLIST_FILE);
        if(!gfwFile.exists()) {
            copyGFWListConfig();
        }

        try {
            loadGFWListFile(gfwFile);
        } catch (IOException e) {
            log.error("Read GFWList file occur a exception", e);
            System.exit(1);
        }

        File cnipv4file = new File(cfg.configPath(), CNIPV4_FILE);
        if(!cnipv4file.exists()) {
            copyCNIPv4Config();
        }

        try {
            loadIPv4CNAddressFile(cnipv4file);
        } catch (IOException e) {
            log.error("Read CN IPv4 file occur a exception", e);
            System.exit(1);
        }


        File file = new File(cfg.configPath(), PAC_CONFIG_FILE);
        if(!file.exists()) {
            try {
                this.proxyMode = PROXY_GFW_LIST;
                makeTemplatePACSettingFile(file);
            } catch (IOException e) {
                throw new ConfigInitializationException("Can not create new File at " + file.getAbsolutePath());
            }
        } else {
            if(file.isDirectory()) {
                throw new ConfigInitializationException("File at " + file.getAbsolutePath() + " is a Directory!");
            }

            try (FileInputStream is = new FileInputStream(file);
                 Scanner sc = new Scanner(is)) {
                String s = sc.next();
                switch (s) {
                    case "no": this.proxyMode = PROXY_NO; break;
                    case "pac": this.proxyMode = PROXY_GFW_LIST; break;
                    case "global": this.proxyMode = PROXY_GLOBAL; break;
                    case "noncn": this.proxyMode = PROXY_NON_CN; break;
                    default: {
                        log.warn("Config file pac setting is not correct, only 'no' / 'pac' / 'global' / 'noncn'");
                        this.proxyMode = PROXY_GFW_LIST;
                    }
                }
            } catch (IOException e) {
                throw new ConfigInitializationException();
            }
        }
    }

    /**
     * 加载GFWList文件
     * @param f GFWList文件路径
     * @throws IOException 加载错误
     */
    private void loadGFWListFile(File f) throws IOException {
        NavigableSet<String> set = new TreeSet<>();
        try(FileInputStream fis = new FileInputStream(f);
            Scanner sc = new Scanner(fis)) {
            while (sc.hasNext()) {
                String host = sc.next();
                if(BaseUtils.isIPAddress(host)) {
                    set.add(host);
                } else if (BaseUtils.isHostName(host)) {
                    set.add(BaseUtils.reverseString(host));
                }
            }
        }

        this.proxySet = Collections.unmodifiableNavigableSet(set);
        log.info("GFWList size: {}", proxySet.size());
    }

    /**
     * 加载中国IPV4地址列表文件
     * @param f cnipv4.txt文件路径
     * @throws IOException 加载错误
     */
    private void loadIPv4CNAddressFile(File f) throws IOException {
        Pattern tar = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/\\d{1,2}");
        NavigableMap<Integer, Integer> map = new TreeMap<>();
        try(FileInputStream fis = new FileInputStream(f);
            Scanner sc = new Scanner(fis)) {
            while (sc.hasNext(tar)) {
                String str = sc.next(tar);
                int idx = str.lastIndexOf('/');
                int ip = BaseUtils.parseIPv4StringToInteger(str.substring(0, idx));
                int mask = 0xFFFFFFFF << (32 - Integer.parseInt(str.substring(idx + 1)));
                map.put(ip, mask);
            }
        }

        this.whiteListMap = Collections.unmodifiableNavigableMap(map);
        log.info("CNIPv4 List size: {}", map.size());
    }



    @Override
    public boolean canSave() {
        return true;
    }

    @Override
    public void save() throws Exception {
        GlobalConfig cfg = configManager.getConfig(GlobalConfig.NAME, GlobalConfig.class);

        File f = new File(cfg.configPath(), PAC_CONFIG_FILE);
        String content;
        switch (this.proxyMode) {
            case PROXY_NO: content = "no"; break;
            case PROXY_GFW_LIST: content = "pac"; break;
            case PROXY_GLOBAL: content = "global"; break;
            case PROXY_NON_CN: content = "noncn"; break;
            default:
                content = "";
        }

        try(FileWriter writer = new FileWriter(f)) {
            writer.write(content);
        }
    }

    public int getProxyMode() {
        return proxyMode;
    }


    public void setProxyMode(int proxyMode) {
        if(this.proxyMode == proxyMode)
            return;

        if(proxyMode < 0 || proxyMode > 3)
            throw new IllegalArgumentException();

        this.proxyMode = proxyMode;
        configManager.updateConfig(this);
    }

    boolean needProxy(String host) {
        if(proxyMode == PROXY_NO) {
            return false;
        }

        if(proxyMode == PROXY_GLOBAL) {
            return true;
        }

        if(proxyMode == PROXY_GFW_LIST) {
            if(BaseUtils.isIPAddress(host)) {
                return proxySet.contains(host);
            }

            String rh = BaseUtils.reverseString(host);
            String fl = proxySet.floor(rh);
            return fl != null && rh.startsWith(fl);
        }

        if(proxyMode == PROXY_NON_CN) {
            int ip;
            if(BaseUtils.isHostName(host)) {
                try {
                    byte[] b = InetAddress.getByName(host).getAddress();
                    if(b.length > 4)  //IPV6暂时全局代理
                        return true;
                    ip = BaseUtils.parseByteArrayToIPv4Integer(b);
                } catch (UnknownHostException e) {
                    log.warn("Unknown host name {}", host);
                    return false;
                }

            } else if(BaseUtils.isIPv4Address(host)) {
                ip = BaseUtils.parseIPv4StringToInteger(host);
            } else {
                return true; //暂时IPV6统一代理
            }

            Map.Entry<Integer, Integer> entry = whiteListMap.floorEntry(ip);
            if(entry == null) {
                return true;
            }

            int mask = entry.getValue();
            int net = entry.getKey();

            return !BaseUtils.isIPv4AddressInRange(ip, net, mask);
        }


        throw new IllegalStateException("ProxyMode is not correct");
    }

    private void makeTemplatePACSettingFile(File file) throws IOException {
        String content = "pac";
        ByteBuffer buf = ByteBuffer.allocate(content.length());
        buf.put(content.getBytes(StandardCharsets.US_ASCII));
        try(FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            buf.rewind();
            ch.write(buf);
        }
    }

    private void copyGFWListConfig() {
        log.info("Can not found GFWList file on User DIR, ready to copy default file to User DIR.");

        byte[] b = new byte[1024000];
        try(InputStream is = configManager.loadResource(configManager.getSystemProperties("pac.gfwlist.config.url"))) {
            int r = is.read(b);
            String str = new String(b, 0, r, DEFAULT_CONFIG_ENCODING);
            GlobalConfig cfg = configManager.getConfig(GlobalConfig.NAME, GlobalConfig.class);
            File f = new File(cfg.configPath(), GFWLIST_FILE);
            try(FileWriter fw = new FileWriter(f)) {
                fw.write(str);
            }
        } catch (IOException e) {
            log.error("Can not find default pac file", e);
            System.exit(1);
        }
    }

    private void copyCNIPv4Config() {
        log.info("Can not found China IPv4 Address file on User DIR, ready to copy default file to User DIR.");
        byte[] b = new byte[1024000];
        try(InputStream is = configManager.loadResource(configManager.getSystemProperties("pac.cnipv4.config.url"))) {
            int r = is.read(b);
            String str = new String(b, 0, r, StandardCharsets.US_ASCII);
            GlobalConfig cfg = configManager.getConfig(GlobalConfig.NAME, GlobalConfig.class);
            File f = new File(cfg.configPath(), CNIPV4_FILE);
            try(FileWriter fw = new FileWriter(f)) {
                fw.write(str);
            }
        } catch (IOException e) {
            log.error("Can not find default CN IPv4 file", e);
            System.exit(1);
        }
    }
}
