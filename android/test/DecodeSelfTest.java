import com.google.zxing.*;
import com.google.zxing.common.*;
import com.google.zxing.oned.Code128Writer;
import java.util.*;

/**
 * 解码自测（不依赖 Android）：验证 Y 平面旋转 + ZXing 解码的方向与索引是否正确。
 * 用法：java -cp ../libs/core.jar DecodeSelfTest.java
 * 期望：第 1/3/4/6 项能解出 SN，第 2 项（横放）失败，第 5 项差异像素为 0。
 */
public class DecodeSelfTest {
    static MultiFormatReader normal() {
        Map<DecodeHintType,Object> h = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> f = new ArrayList<>();
        f.add(BarcodeFormat.CODE_128); f.add(BarcodeFormat.CODE_39); f.add(BarcodeFormat.CODE_93);
        f.add(BarcodeFormat.EAN_13); f.add(BarcodeFormat.EAN_8); f.add(BarcodeFormat.ITF);
        f.add(BarcodeFormat.CODABAR); f.add(BarcodeFormat.UPC_A); f.add(BarcodeFormat.UPC_E);
        h.put(DecodeHintType.POSSIBLE_FORMATS, f);
        h.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        h.put(DecodeHintType.TRY_HARDER, Boolean.FALSE);
        MultiFormatReader r = new MultiFormatReader();
        r.setHints(h);
        return r;
    }

    static byte[] rot(byte[] src, int w, int h, boolean cw) {
        byte[] out = new byte[w*h];
        for (int y=0;y<h;y++){ int row=y*w;
            if (cw){ for(int x=0;x<w;x++) out[x*h + (h-1-y)] = src[row+x]; }
            else { for(int x=0;x<w;x++) out[(w-1-x)*h + y] = src[row+x]; }
        }
        return out;
    }

    static String tryDecode(byte[] buf, int w, int h, MultiFormatReader r) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(buf, w, h, 0, 0, w, h, false);
            BinaryBitmap bmp = new BinaryBitmap(new HybridBinarizer(src));
            Result res = r.decodeWithState(bmp);
            return res == null ? null : res.getText();
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName();
        } finally { r.reset(); }
    }

    public static void main(String[] a) throws Exception {
        String code = "LXT261027AC0000030";
        int BW = 640, BH = 200;
        BitMatrix bm = new Code128Writer().encode(code, BarcodeFormat.CODE_128, BW, BH);
        byte[] upright = new byte[BW*BH];
        for (int y=0;y<BH;y++) for (int x=0;x<BW;x++) upright[y*BW+x] = bm.get(x,y) ? (byte)0 : (byte)255;

        MultiFormatReader r = normal();
        long t0=System.currentTimeMillis();
        String ok = tryDecode(upright, BW, BH, r);
        System.out.println("1) 正立图 640x200 -> " + ok + "  (" + (System.currentTimeMillis()-t0) + "ms)");

        // 模拟相机缓冲：把正立条码旋转 90 度放进一个 720x1280 的传感器帧里（横放）
        byte[] buf = new byte[720*1280];
        Arrays.fill(buf, (byte)255);
        byte[] side = rot(upright, BW, BH, true);   // dims 200x640
        int offX = (720-200)/2, offY = (1280-640)/2;
        for (int y=0;y<640;y++) for (int x=0;x<200;x++) buf[(offY+y)*720 + offX+x] = side[y*200+x];

        System.out.println("2) 相机帧原样(横放) 720x1280 -> " + tryDecode(buf,720,1280,normal()));
        t0=System.currentTimeMillis();
        byte[] cw = rot(buf,720,1280,true);   // dims 1280x720
        System.out.println("3) 顺时针 90 度 1280x720 -> " + tryDecode(cw,1280,720,normal()) + "  ("+(System.currentTimeMillis()-t0)+"ms)");
        t0=System.currentTimeMillis();
        byte[] ccw = rot(buf,720,1280,false); // dims 1280x720
        System.out.println("4) 逆时针 90 度 1280x720 -> " + tryDecode(ccw,1280,720,normal()) + "  ("+(System.currentTimeMillis()-t0)+"ms)");

        // 来回旋转是否可逆
        byte[] back = rot(cw,1280,720,false);
        int diff=0; for(int i=0;i<buf.length;i++) if(back[i]!=buf[i]) diff++;
        System.out.println("5) 旋转可逆性: 差异像素 " + diff);

        // 大尺寸真实相机帧（1280x720 -> 旋转后 720x1280）
        byte[] big = new byte[1280*720];
        Arrays.fill(big,(byte)255);
        BitMatrix bm2 = new Code128Writer().encode(code, BarcodeFormat.CODE_128, 620, 200);
        byte[] up2 = new byte[620*200];
        for (int y=0;y<200;y++) for(int x=0;x<620;x++) up2[y*620+x]= bm2.get(x,y)?(byte)0:(byte)255;
        byte[] sd2 = rot(up2,620,200,true); // 200x620
        int ox=(1280-200)/2, oy=(720-620)/2;
        for(int y=0;y<620;y++) for(int x=0;x<200;x++) big[(oy+y)*1280+ox+x]= sd2[y*200+x];
        t0=System.currentTimeMillis();
        byte[] bigcw = rot(big,1280,720,true); // 720x1280
        System.out.println("6) 大帧顺时针 720x1280 -> " + tryDecode(bigcw,720,1280,normal()) + "  ("+(System.currentTimeMillis()-t0)+"ms)");
    }
}
