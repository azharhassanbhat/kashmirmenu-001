import com.kashmirmenu.app.enums.RoleName; import com.kashmirmenu.app.exception.AccessDeniedException; import com.kashmirmenu.app.service.StaffRules;
import static com.kashmirmenu.app.enums.RoleName.*;
public class CheckStaffRules { static int n=0; static void ok(boolean c,String m){ if(!c) throw new AssertionError(m); n++; }
 public static void main(String[] a){
  for(RoleName t:new RoleName[]{MANAGER,CHEF,WAITER,CASHIER}){ ok(StaffRules.canManage(RESTAURANT_OWNER,t),"owner->"+t); ok(StaffRules.canManage(ADMIN,t),"admin->"+t); }
  for(RoleName t:new RoleName[]{CHEF,WAITER,CASHIER}) ok(StaffRules.canManage(MANAGER,t),"manager->"+t);
  ok(!StaffRules.canManage(MANAGER,MANAGER),"manager must not manage managers");
  for(RoleName actor:RoleName.values()) for(RoleName t:new RoleName[]{RESTAURANT_OWNER,ADMIN,PLATFORM_OWNER,CUSTOMER}) ok(!StaffRules.canManage(actor,t),actor+" must never manage "+t);
  for(RoleName actor:new RoleName[]{CHEF,WAITER,CASHIER,CUSTOMER,PLATFORM_OWNER}) for(RoleName t:RoleName.values()) ok(!StaffRules.canManage(actor,t),actor+" has no staff powers over "+t);
  ok(!StaffRules.canManage(null,WAITER)&&!StaffRules.canManage(MANAGER,null),"nulls refused");
  try{StaffRules.requireCanAssign(MANAGER,MANAGER);throw new AssertionError("x");}catch(AccessDeniedException e){ok(e.getMessage().contains("manager"),"message names role");}
  StaffRules.requireCanAssign(RESTAURANT_OWNER,MANAGER); n++;
  ok(StaffRules.passwordProblem("Waiter2026","w@x.com","Sam")==null,"good password");
  ok(StaffRules.passwordProblem(null,"w@x.com","Sam")!=null && StaffRules.passwordProblem("   ","w@x.com","Sam")!=null,"blank");
  ok(StaffRules.passwordProblem("abc12","w@x.com","Sam").contains("8"),"short");
  ok(StaffRules.passwordProblem("a1".repeat(51),"w@x.com","Sam")!=null,"too long");
  ok(StaffRules.passwordProblem("12345678","w@x.com","Sam").contains("letter"),"no letter");
  ok(StaffRules.passwordProblem("abcdefgh","w@x.com","Sam").contains("number"),"no digit");
  ok(StaffRules.passwordProblem("sam12345","w@x.com","sam12345")!=null,"same as name");
  ok(StaffRules.passwordProblem("waiter12","waiter12@x.com","Sam")!=null,"same as email local part");
  ok(StaffRules.passwordProblem("WAITER12@X.COM","waiter12@x.com","Sam")!=null,"same as email");
  ok(StaffRules.passwordProblem("a1".repeat(50),"w@x.com","Sam")==null,"exactly 100 ok");
  System.out.println("STAFF RULES: PASSED "+n+" assertions"); } }
