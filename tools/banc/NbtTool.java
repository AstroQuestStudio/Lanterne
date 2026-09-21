import java.io.*; import java.util.*; import java.util.zip.*;
/** Petit lecteur/éditeur NBT (level.dat) : dump, ou set chemin=valeur (Long/Int/Byte/String existants). */
public class NbtTool {
  static void ind(int n){ for(int i=0;i<n;i++) System.out.print("  "); }
  static Object read(DataInputStream in, int type, String path, Map<String,Object[]> idx) throws IOException {
    switch(type){
      case 1: { byte v=in.readByte(); return v; }
      case 2: return in.readShort();
      case 3: return in.readInt();
      case 4: return in.readLong();
      case 5: return in.readFloat();
      case 6: return in.readDouble();
      case 7: { int n=in.readInt(); in.skipBytes(n); return "byte[" + n + "]"; }
      case 8: return in.readUTF();
      case 9: { int t=in.readByte(); int n=in.readInt(); List<Object> l=new ArrayList<>(); for(int i=0;i<n;i++) l.add(read(in,t,path+"["+i+"]",idx)); return l; }
      case 10: { Map<String,Object> m=new LinkedHashMap<>(); while(true){ int t=in.readByte(); if(t==0)break; String k=in.readUTF(); m.put(k, read(in,t,path+"/"+k,idx)); } return m; }
      case 11: { int n=in.readInt(); in.skipBytes(4*n); return "int[" + n + "]"; }
      case 12: { int n=in.readInt(); in.skipBytes(8*n); return "long[" + n + "]"; }
    } throw new IOException("type " + type);
  }
  static void dump(Object o, int d, String key){
    if(o instanceof Map){ System.out.println(key+"{"); for(var e:((Map<String,Object>)o).entrySet()){ ind(d+1); dump(e.getValue(), d+1, e.getKey()+": "); } ind(d); System.out.println("}"); }
    else if(o instanceof List && ((List)o).size()<8){ System.out.println(key+o); }
    else if(o instanceof List){ System.out.println(key+"list["+((List)o).size()+"]"); }
    else System.out.println(key+o+" ("+o.getClass().getSimpleName()+")");
  }
  public static void main(String[] a) throws Exception {
    try(DataInputStream in=new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(a[0]))))){
      int t=in.readByte(); in.readUTF(); Object root=read(in,t,"",null); dump(root,0,"");
    }
  }
}
