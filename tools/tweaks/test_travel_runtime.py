"""Run actual travel controllers and charging Parcel codec against deterministic Android fixtures.
The frozen 12-field getter matches the offline emulator capture. Fake write acknowledgements
are configured behavior; the recordings do not establish a real VCU setter acknowledgement.
"""
from pathlib import Path
import subprocess, shutil, json
ROOT=Path(__file__).resolve().parents[2]
work=ROOT/'build/travel-runtime-contract'
if work.exists():shutil.rmtree(work)
work.mkdir(parents=True)
stubs={
'android/os/SystemClock':'''public class SystemClock {public static long now=100000; public static long elapsedRealtime(){return now;}}''',
'android/os/Handler':'''import java.util.*; public class Handler {
 private static final List<Object[]> q=new ArrayList<>();public Handler(){}public Handler(Looper l){}public boolean post(Runnable r){return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long d){q.add(new Object[]{r,SystemClock.now+d});return true;}
 public void removeCallbacks(Runnable r){q.removeIf(x->x[0]==r);}
 public void drain(){for(boolean again=true;again;){again=false;for(Object[] x:new ArrayList<>(q))if((Long)x[1]<=SystemClock.now&&q.remove(x)){((Runnable)x[0]).run();again=true;}}}
 public void advance(long ms){SystemClock.now+=ms;drain();}}''',
'android/os/Looper': 'public class Looper {}',
'android/os/HandlerThread': 'public class HandlerThread extends Thread {public HandlerThread(String n){super(n);}public Looper getLooper(){return new Looper();}public void run(){}}',
'android/os/RemoteException': 'public class RemoteException extends Exception {public RemoteException(String s){super(s);}}',
'android/os/Binder': '''public class Binder implements IBinder {public boolean transact(int c,Parcel d,Parcel r,int f)throws Exception{return onTransact(c,d,r,f);} protected boolean onTransact(int c,Parcel d,Parcel r,int f)throws RemoteException{return false;}}''',
'android/os/IBinder':'''public interface IBinder {int INTERFACE_TRANSACTION=1598968902,FLAG_ONEWAY=1;boolean transact(int code,Parcel data,Parcel reply,int flags)throws Exception;}''',
'android/os/Parcel':'''public class Parcel {public String token;public int[] array;public boolean ok;public int extra;public IBinder binder;
 public static Parcel obtain(){return new Parcel();}public void recycle(){}public void writeInterfaceToken(String s){token=s;}
 public void writeIntArray(int[] a){array=a.clone();}public int[] createIntArray(){return array==null?null:array.clone();}
 public void enforceInterface(String s){if(!s.equals(token))throw new SecurityException();}public void writeString(String s){}public void writeNoException(){ok=true;}public void writeStrongBinder(IBinder b){binder=b;}public int dataAvail(){return extra;}public void readException(){if(!ok)throw new IllegalStateException("Binder refused");}}''',
'android/content/ComponentName':'''public class ComponentName {private final String p;public ComponentName(String p){this.p=p;}public String getPackageName(){return p;}}''',
'android/app/ActivityManager':'''import java.util.*;import android.content.ComponentName;
public class ActivityManager {public static String top="com.tilepad";public static class RunningTaskInfo{public ComponentName topActivity;}
 public List<RunningTaskInfo> getRunningTasks(int n){RunningTaskInfo t=new RunningTaskInfo();t.topActivity=new ComponentName(top);return Arrays.asList(t);}}''',
'android/content/pm/ApplicationInfo':'''public class ApplicationInfo {public int uid=10206;}''',
'android/content/pm/PackageManager':'''public class PackageManager {public ApplicationInfo getApplicationInfo(String p,int flags){if(!p.equals("com.hkmc.daudio.app.ev3"))throw new AssertionError();return new ApplicationInfo();}}''',
'android/content/Context':'''public class Context {public static final String ACTIVITY_SERVICE="activity";public Object getSystemService(String n){return new android.app.ActivityManager();}
 public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}''',
'org/xmlpull/v1/XmlPullParser':'''public interface XmlPullParser {int END_DOCUMENT=1,START_TAG=2,DOCDECL=10;
 void setInput(java.io.Reader r)throws Exception;int getEventType();int next()throws Exception;String getName();String getAttributeValue(String ns,String name);}''',
'android/util/Xml':'''import org.xmlpull.v1.XmlPullParser;import javax.xml.stream.*;
public class Xml {public static XmlPullParser newPullParser(){return new XmlPullParser(){XMLStreamReader r;
 public void setInput(java.io.Reader s)throws Exception{r=XMLInputFactory.newInstance().createXMLStreamReader(s);}
 public int getEventType(){int t=r.getEventType();return t==XMLStreamConstants.START_ELEMENT?START_TAG:t==XMLStreamConstants.END_DOCUMENT?END_DOCUMENT:t==XMLStreamConstants.DTD?DOCDECL:0;}
 public int next()throws Exception{r.next();return getEventType();}public String getName(){return r.getLocalName();}
 public String getAttributeValue(String ns,String name){return r.getAttributeValue(ns,name);}};}}''',
'org/json/JSONObject':'''public class JSONObject {private final String s;public JSONObject(String s){this.s=s;}
 public boolean has(String n){return s.contains(n);}public int getInt(String n){java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+n+"\\\"\\\\s*:\\\\s*([0-9]+)").matcher(s);if(!m.find())throw new IllegalArgumentException();return Integer.parseInt(m.group(1));}}''',
'com/hkmc/Feature':'''public class Feature {public static boolean secure;public static boolean hasFeature(String n){if(!n.equals("com.hkmc.software.security.unece"))throw new AssertionError();return secure;}}''',
'com/tilepad/tweakcore/TweaksCommand':'''public class TweaksCommand {public static Config config=new Config(new java.util.Properties());public static Config load(){return config;}}''',
'com/tilepad/tweakcore/Platform':'''import java.io.*;import java.util.*;import android.os.*;
public class Platform {
 public static final File STATE=new File(System.getProperty("fixture.state"));
 public static int writes,reads,gpsReads,prefReads,launches,aa,cp;public static boolean ack=true,failWrite,failBroadcast,failProjection,extra;
 public static IBinder listener;public static int registers,removes,freshRequests;public static boolean missingFeedback;public static Runnable feedbackHook;public static int hookAfter;
 public static int[] schedule={11,0,4,3,0,0,0,0,0,0,0,0};
 public static double lat,lon,accuracy=3;public static long age;public static String preference="<map><int name=\\"economic_only_economic_mode\\" value=\\"1\\"/></map>";
 public static final List<int[]> requests=new ArrayList<>();public static final List<String> messages=new ArrayList<>();
 public static void requireVehicle(){}public static String boot(){return "fixture-boot";}
 public static void status(String id,String s){messages.add(id+": "+s);}
 public static int integer(String name,String descriptor,int code)throws Exception{
  if(failProjection)throw new IOException("services starting");
  if(name.equals("gpm")&&descriptor.equals("com.hkmc.gpm.IGpmInterfaces")&&code==1)return aa;
  if(name.equals("dipo")&&descriptor.equals("android.dipo.IDiPOManager")&&code==2)return cp;
  throw new AssertionError("Unmapped read "+name+code);}
 public static IBinder service(String name,String descriptor){
  if(!name.equals("com.hkmc.micom.hev")||!descriptor.equals("com.hkmc.hev.IHEVService"))throw new AssertionError();
  return (code,data,reply,flags)->{if(!descriptor.equals(data.token)||flags!=0)throw new AssertionError();
   if(code==1){registers++;listener=data.binder;}
   else if(code==2){removes++;listener=null;}
   else if(code==14){reads++;freshRequests++;if(!missingFeedback){Parcel event=Parcel.obtain(),ack=Parcel.obtain();event.token="com.hkmc.hev.IHEVListener";event.array=schedule.clone();event.extra=extra?1:0;listener.transact(4,event,ack,0);if(!ack.ok)throw new AssertionError();}if(feedbackHook!=null&&--hookAfter==0){Runnable hook=feedbackHook;feedbackHook=null;hook.run();}}
   else if(code==30){writes++;requests.add(data.array.clone());if(failWrite)throw new IOException("uncertain write");
    if(data.array.length!=9)throw new AssertionError("nine-field request required");
    for(int i=0;i<8;i++)if(data.array[i]!=schedule[i])throw new AssertionError("time field changed");
    if(ack)System.arraycopy(data.array,0,schedule,0,9);
   }else throw new AssertionError("Unmapped charging call "+code);
   reply.ok=true;return true;};}
 public static String exec(String... args)throws Exception{
  if(args[0].equals("dumpsys")){if(!args[1].equals("location"))throw new AssertionError();gpsReads++;
   return " gps: Location[gps "+lat+","+lon+" acc="+accuracy+" et=+"+(SystemClock.now-age)+"ms]";}
  if(args[0].equals("/system/xbin/nemesis")){prefReads++;if(!args[1].equals("root")||!args[2].equals("10206")||!args[4].startsWith("cat "))throw new AssertionError("preferences must only be read");return preference;}
  if(args[0].equals("am")){if(!args[1].equals("broadcast")||!args[2].equals("-a")||!args[3].startsWith("com.hkmc.intent.action."))throw new AssertionError();
   launches++;if(failBroadcast)throw new IOException("broadcast failed");return "done";}
  throw new AssertionError(Arrays.toString(args));}
}'''
}
for name,body in stubs.items():
 p=work/'src'/f'{name}.java';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('package '+name.rsplit('/',1)[0].replace('/','.')+';\n'+body)
test=work/'src/com/tilepad/tweakcore/TravelRuntimeTest.java'
test.write_text('''package com.tilepad.tweakcore;
import java.util.*;import java.lang.reflect.*;import java.io.*;import android.os.*;import android.content.*;import android.app.*;
public class TravelRuntimeTest {
 static void check(boolean v,String message){if(!v)throw new AssertionError(message);}
 static void supported(Class<?> c)throws Exception{Field f=c.getDeclaredField("checked");f.setAccessible(true);f.set(null,true);}
 static Config config(boolean charge,boolean projection){Config c=new Config(new Properties());c.set("chargeaway",Boolean.toString(charge));c.set("projection",Boolean.toString(projection));c.set("homeLat","0");c.set("homeLon","0");TweaksCommand.config=c;return c;}
 static void fix(double miles){Platform.lat=miles*ChargeAwayPolicy.MILE_M/111195.0802;Platform.lon=0;Platform.age=0;Platform.accuracy=3;}
 public static void main(String[] args)throws Exception{
  Platform.STATE.mkdirs();supported(ScheduledCharging.class);supported(ProjectionController.class);int passed=0;
  Context context=new Context();Handler h=new Handler();Config c=config(true,false);fix(0);
  ChargeAwayController charge=new ChargeAwayController(context);charge.configure(c);h.drain();
  check(Platform.writes==1&&Platform.schedule[8]==3,"Home restores only-off-peak mode");int gps=Platform.gpsReads;
  h.advance(9999);check(Platform.gpsReads==gps,"not before ten seconds");h.advance(1);check(Platform.gpsReads==gps+1&&Platform.writes==1,"poll at ten seconds, no redundant write");passed++;
  fix(1.7);h.advance(10000);check(Platform.schedule[8]==0&&Platform.writes==2,"Away disables");
  fix(1.2);h.advance(10000);check(Platform.schedule[8]==0&&Platform.writes==2,"band retains Away");passed++;
  Config off=config(false,false);charge.configure(off);c=config(true,false);ChargeAwayController restart=new ChargeAwayController(context);restart.configure(c);h.drain();
  check(Platform.writes==2&&Platform.schedule[8]==0,"restart retains persisted Away in band");
  fix(.8);h.advance(10000);check(Platform.schedule[8]==3&&Platform.writes==3,"within one mile restores");passed++;
  fix(3);Platform.age=60001;h.advance(10000);check(Platform.writes==3,"stale location leaves schedule alone");
  Platform.age=0;Platform.accuracy=151;h.advance(10000);check(Platform.writes==3,"inaccurate location leaves schedule alone");passed++;
  Platform.accuracy=3;restart.configure(config(false,false));int prior=Platform.gpsReads;h.advance(10000);check(Platform.gpsReads==prior&&Platform.schedule[8]==3,"disable stops polls and preserves state");passed++;
  new File(Platform.STATE,"charge-away.properties").delete();c=config(true,false);fix(0);Platform.schedule[8]=0;Platform.ack=false;
  ChargeAwayController refusal=new ChargeAwayController(context);refusal.configure(c);h.drain();int base=Platform.writes;
  h.advance(10000);h.advance(10000);h.advance(10000);h.advance(10000);
  check(Platform.writes==base+2,"three requests maximum without feedback");refusal.configure(config(false,false));passed++;
  new File(Platform.STATE,"charge-away.properties").delete();c=config(true,false);Platform.failWrite=true;
  ChargeAwayController uncertain=new ChargeAwayController(context);uncertain.configure(c);h.drain();base=Platform.writes;
  h.advance(10000);h.advance(10000);h.advance(10000);check(Platform.writes==base+2,"uncertain writes also consume retry budget");
  uncertain.configure(config(false,false));Platform.failWrite=false;Platform.ack=true;passed++;
  new File(Platform.STATE,"charge-away.properties").delete();c=config(true,false);Platform.extra=true;
  ChargeAwayController malformed=new ChargeAwayController(context);base=Platform.writes;malformed.configure(c);h.drain();check(Platform.writes==base,"extra Parcel data refuses write");
  malformed.configure(config(false,false));Platform.extra=false;passed++;
  Platform.preference="<map><int name=\\"economic_only_economic_mode\\" value=\\"0\\"/></map>";check(ScheduledCharging.preference(context)==0,"stock prefer-off-peak remains selected");
  Platform.preference="<map><int value=\\"1\\" name=\\"economic_only_economic_mode\\"/></map>";check(ScheduledCharging.preference(context)==1,"attribute order independent");
  Platform.preference="<map/>";check(ScheduledCharging.preference(context)==0,"same missing-key default as OEM");passed++;
  Platform.preference="<map><int name=\\"economic_only_economic_mode\\" value=\\"9\\"/></map>";
  try{ScheduledCharging.preference(context);throw new AssertionError("invalid preference accepted");}catch(IOException expected){}passed++;
  new File(Platform.STATE,"charge-away.properties").delete();c=config(true,false);Platform.schedule[8]=0;Platform.missingFeedback=true;base=Platform.writes;
  ChargeAwayController missing=new ChargeAwayController(context);missing.configure(c);h.drain();check(Platform.writes==base&&Platform.listener==null,"no setting changes without fresh callback, listener removed");missing.configure(config(false,false));h.drain();Platform.missingFeedback=false;passed++;
  Platform.preference="<map><int name=\\"economic_only_economic_mode\\" value=\\"1\\"/></map>";
  new File(Platform.STATE,"charge-away.properties").delete();c=config(true,false);Platform.schedule[8]=0;base=Platform.writes;
  Platform.hookAfter=2;Platform.feedbackHook=()->{config(false,false);};
  ChargeAwayController disabledDuringRead=new ChargeAwayController(context);disabledDuringRead.configure(c);h.drain();
  check(Platform.writes==base,"disable during final fresh read prevents queued write");disabledDuringRead.configure(config(false,false));h.drain();passed++;
  new File(Platform.STATE,"charge-away.properties").delete();c=config(true,false);base=Platform.writes;
  Platform.hookAfter=2;Platform.feedbackHook=()->{TweaksCommand.config.set("homeLat","1");};
  ChargeAwayController movedHome=new ChargeAwayController(context);movedHome.configure(c);h.drain();
  check(Platform.writes==base,"Home change during final fresh read prevents old-Home write");movedHome.configure(config(false,false));h.drain();passed++;
  new File(Platform.STATE,"charge-away.properties").delete();c=config(true,false);base=Platform.writes;
  Platform.hookAfter=1;Platform.feedbackHook=()->{Platform.schedule[0]=12;};
  ChargeAwayController changedTimes=new ChargeAwayController(context);changedTimes.configure(c);h.drain();
  check(Platform.writes==base&&Platform.schedule[0]==12,"changed times between fresh reads refuse stale write");changedTimes.configure(config(false,false));h.drain();passed++;
  check(Platform.registers==Platform.removes,"all temporary charging listeners removed");
  ProjectionController projection=new ProjectionController(context,h);c=config(false,true);projection.configure(true);Platform.aa=4;projection.event();h.advance(250);
  check(Platform.launches==1,"Android Auto launch on availability");projection.event();projection.event();h.advance(250);check(Platform.launches==1,"duplicate notices deduplicated");passed++;
  ProjectionController reconnect=new ProjectionController(context,h);reconnect.configure(true);h.advance(250);check(Platform.launches==1,"service restart doesn't reopen");passed++;
  Platform.aa=0;reconnect.event();h.advance(250);Platform.cp=4;reconnect.event();h.advance(250);check(Platform.launches==2,"new CarPlay connection opens");
  ActivityManager.top="com.google.android.projection.sink";Platform.cp=0;reconnect.event();h.advance(250);Platform.aa=4;reconnect.event();h.advance(250);check(Platform.launches==2,"already foreground left alone");passed++;
  ActivityManager.top="com.tilepad";Platform.failProjection=true;reconnect.event();h.advance(250);Platform.failProjection=false;h.advance(5000);check(Platform.launches==2,"unknown service doesn't manufacture reconnection");passed++;
  reconnect.configure(false);int opens=Platform.launches;Platform.aa=0;Platform.cp=4;reconnect.event();h.advance(10000);check(Platform.launches==opens,"disabled doesn't launch");passed++;
  ActivityManager.top="com.hkmc.system.app.rearcamera";ProjectionController camera=new ProjectionController(context,h);camera.configure(true);h.advance(250);
  check(Platform.launches==opens,"camera is not interrupted");ActivityManager.top="com.tilepad";h.advance(1000);check(Platform.launches==opens+1,"projection opens after camera closes");passed++;
  camera.configure(false);Platform.failBroadcast=true;ProjectionController retry=new ProjectionController(context,h);retry.configure(true);h.advance(250);opens=Platform.launches;
  Platform.failBroadcast=false;h.advance(5000);check(Platform.launches==opens+1,"failed dispatch retries instead of consuming the connection");retry.event();h.advance(250);check(Platform.launches==opens+1,"successful retry is deduplicated");passed++;
  System.out.println(passed+" travel controller/Parcel scenarios passed (mapped fixtures; no real vehicle writes)");
 }
}''')
production=ROOT/'tweaks-core/src/main/java/com/tilepad/tweakcore'
names=['Config','Files','VehicleLocation','VehicleLocationSource','ChargeAwayPolicy','ChargeAwayController','ScheduledCharging','ProjectionPolicy','ProjectionController']
classes=work/'classes';classes.mkdir()
subprocess.run(['javac','--release','8','-d',str(classes),*[str(p) for p in (work/'src').rglob('*.java')],*[str(production/(n+'.java')) for n in names]],check=True)
r=subprocess.run(['java','-Dfixture.state='+str(work/'state'),'-cp',str(classes),'com.tilepad.tweakcore.TravelRuntimeTest'],check=True,capture_output=True,text=True,timeout=30)
(work/'results.json').write_text(json.dumps({'scope':'mapped Android fixtures, not real vehicle writes','passed':True,'output':r.stdout},indent=2)+'\n');print(r.stdout)
