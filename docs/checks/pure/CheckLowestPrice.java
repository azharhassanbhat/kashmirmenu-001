import com.kashmirmenu.app.enums.OptionKind; import com.kashmirmenu.app.service.OptionPricing; import com.kashmirmenu.app.service.OptionPricing.*;
import java.math.BigDecimal; import java.util.*;
public class CheckLowestPrice { static BigDecimal d(String s){return new BigDecimal(s);} static Opt o(long i,String n,String x){return new Opt(i,n,d(x),true);}
 static void eq(String exp, BigDecimal got){ if(!d(exp).equals(got)) throw new AssertionError(exp+" vs "+got); }
 public static void main(String[] a){
  var biryani=List.of(new Group(1L,"Portion",OptionKind.SINGLE,true,null,List.of(o(10,"Half","-80.00"),o(11,"Full","0.00"),o(12,"Family","250.00"))),
   new Group(2L,"Extras",OptionKind.MULTI,false,2,List.of(o(20,"Raita","30.00"),o(21,"Egg","20.00"))),
   new Group(3L,"Spice",OptionKind.SINGLE,false,null,List.of(o(30,"Mild","0.00"),o(31,"Hot","0.00"))));
  eq("240.00",OptionPricing.lowestPossiblePrice(d("320.00"),biryani));
  var st=List.of(new Group(1L,"Size",OptionKind.SINGLE,true,null,List.of(o(1,"S","-50.00"),o(2,"B","0.00"))),
   new Group(2L,"Deals",OptionKind.MULTI,false,2,List.of(o(3,"a","-20.00"),o(4,"b","-10.00"),o(5,"c","-5.00"))));
  eq("120.00",OptionPricing.lowestPossiblePrice(d("200.00"),st));
  eq("100.00",OptionPricing.lowestPossiblePrice(d("100.00"),List.of(new Group(1L,"Side",OptionKind.SINGLE,false,null,List.of(o(1,"F","40.00"))))));
  eq("110.00",OptionPricing.lowestPossiblePrice(d("100.00"),List.of(new Group(1L,"Size",OptionKind.SINGLE,true,null,List.of(o(1,"M","10.00"),o(2,"L","30.00"))))));
  System.out.println("LOWEST PRICE: PASSED");
 }}
