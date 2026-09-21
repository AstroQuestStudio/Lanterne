import java.io.*; import java.nio.charset.StandardCharsets; import java.nio.file.*; import java.util.zip.*;
/** Fige l'heure et la meteo d'un monde 26.x : java Freeze.java <dossier monde> <ticks> (patch binaire sur place, memes tailles). */
public class Freeze {
  static byte[] gunzip(Path p) throws IOException { try(GZIPInputStream in=new GZIPInputStream(Files.newInputStream(p))){ return in.readAllBytes(); } }
  static void gzip(Path p, byte[] b) throws IOException { try(GZIPOutputStream o=new GZIPOutputStream(Files.newOutputStream(p))){ o.write(b); } }
  static int find(byte[] b, byte[] pat, int from){ outer: for(int i=from;i<=b.length-pat.length;i++){ for(int j=0;j<pat.length;j++) if(b[i+j]!=pat[j]) continue outer; return i; } return -1; }
  static byte[] tag(int type, String name){ byte[] n=name.getBytes(StandardCharsets.UTF_8); byte[] r=new byte[3+n.length]; r[0]=(byte)type; r[1]=(byte)(n.length>>8); r[2]=(byte)n.length; System.arraycopy(n,0,r,3,n.length); return r; }
  static boolean setByte(byte[] b, String name, int v){ int i=find(b,tag(1,name),0); if(i<0) return false; b[i+3+name.length()]=(byte)v; return true; }
  static boolean setInt(byte[] b, String name, int v, int from){ int i=find(b,tag(3,name),from); if(i<0) return false; int o=i+3+name.length(); b[o]=(byte)(v>>24); b[o+1]=(byte)(v>>16); b[o+2]=(byte)(v>>8); b[o+3]=(byte)v; return true; }
  static boolean setLong(byte[] b, String name, long v, int from){ int i=find(b,tag(4,name),from); if(i<0) return false; int o=i+3+name.length(); for(int k=0;k<8;k++) b[o+k]=(byte)(v>>(56-8*k)); return true; }
  public static void main(String[] a) throws Exception {
    Path world=Path.of(a[0]); long ticks=Long.parseLong(a[1]); Path d=world.resolve("data/minecraft");
    Path rules=d.resolve("game_rules.dat"); byte[] r=gunzip(rules);
    String[] off={"minecraft:advance_time","minecraft:advance_weather","minecraft:spawn_monsters","minecraft:spawn_mobs","minecraft:spawn_wandering_traders","minecraft:spawn_patrols","minecraft:spawn_phantoms","minecraft:spawn_warden"};
    for(String k:off) System.out.println("regle "+k+" -> 0 : "+setByte(r,k,0)); gzip(rules,r);
    Path w=d.resolve("weather.dat"); byte[] wb=gunzip(w);
    System.out.println("raining=0 "+setByte(wb,"raining",0)+" thundering=0 "+setByte(wb,"thundering",0));
    System.out.println("clear_weather_time "+setInt(wb,"clear_weather_time",1_000_000,0)+" rain_time "+setInt(wb,"rain_time",1_000_000,0)+" thunder_time "+setInt(wb,"thunder_time",1_000_000,0)); gzip(w,wb);
    Path c=d.resolve("world_clocks.dat"); byte[] cb=gunzip(c);
    int ow=find(cb,tag(10,"minecraft:overworld"),0);
    System.out.println("overworld.total_ticks="+ticks+" : "+(ow>=0 && setLong(cb,"total_ticks",ticks,ow))); gzip(c,cb);
  }
}
