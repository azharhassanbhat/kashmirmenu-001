import com.kashmirmenu.app.enums.*;
import static com.kashmirmenu.app.enums.ServiceRequestStatus.*;
public class CheckServiceRequestRules {
  static int n=0;
  static void ok(boolean c,String m){ if(!c) throw new AssertionError(m); n++; }
  public static void main(String[] a){
    ok(PENDING.canMoveTo(ACKNOWLEDGED),"p->a"); ok(ACKNOWLEDGED.canMoveTo(IN_PROGRESS),"a->i"); ok(IN_PROGRESS.canMoveTo(COMPLETED),"i->c");
    ok(!PENDING.canMoveTo(COMPLETED),"p->c refused"); ok(!PENDING.canMoveTo(IN_PROGRESS),"p->i refused");
    ok(ACKNOWLEDGED.canMoveTo(COMPLETED),"a->c");
    for(var o: ServiceRequestStatus.OPEN) ok(o.canMoveTo(CANCELLED),"cancel "+o);
    for(var f: new ServiceRequestStatus[]{COMPLETED,CANCELLED}) for(var x: ServiceRequestStatus.values()) if(x!=f) ok(!f.canMoveTo(x),f+"->"+x);
    ok(!IN_PROGRESS.canMoveTo(PENDING),"i->p"); ok(!ACKNOWLEDGED.canMoveTo(PENDING),"a->p");
    for(var s: ServiceRequestStatus.values()) ok(s.canMoveTo(s),"self "+s);
    for(var t: ServiceRequestType.values()){ ok((t==ServiceRequestType.OTHER)==t.allowsSeveralOpen(),"several "+t); ok(!t.label().isBlank(),"label "+t);}
    System.out.println("PASSED "+n+" assertions");
  }
}
