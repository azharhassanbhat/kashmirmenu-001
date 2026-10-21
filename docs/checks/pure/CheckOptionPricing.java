import com.kashmirmenu.app.enums.OptionKind;
import com.kashmirmenu.app.exception.BadRequestException;
import com.kashmirmenu.app.service.OptionPricing;
import com.kashmirmenu.app.service.OptionPricing.*;
import java.math.BigDecimal; import java.util.*;
public class CheckOptionPricing {
  static int n=0;
  static void ok(boolean c,String m){ if(!c) throw new AssertionError(m); n++; }
  static void bad(Runnable r,String m){ try{ r.run(); }catch(BadRequestException e){ n++; return;} throw new AssertionError("expected refusal: "+m); }
  static BigDecimal d(String s){return new BigDecimal(s);}
  static Opt o(long id,String nm,String dl,boolean av){return new Opt(id,nm,d(dl),av);}
  public static void main(String[] a){
    List<Group> g=List.of(
      new Group(1L,"Portion",OptionKind.SINGLE,true,null,List.of(o(10,"Half","-80.00",true),o(11,"Full","0.00",true),o(12,"Family","250.00",true))),
      new Group(2L,"Extras",OptionKind.MULTI,false,2,List.of(o(20,"Raita","30.00",true),o(21,"Egg","20.00",true),o(22,"Salan","25.00",true),o(23,"Papad","10.00",false))),
      new Group(3L,"Spice",OptionKind.SINGLE,false,null,List.of(o(30,"Mild","0.00",true),o(31,"Hot","0.00",true))));
    BigDecimal B=d("320.00");
    ok(d("620.00").equals(OptionPricing.price(B,g,List.of(12L,20L,21L,31L)).unitPrice()),"sum");
    ok(d("240.00").equals(OptionPricing.price(B,g,List.of(10L)).unitPrice()),"half");
    ok(d("320.00").equals(OptionPricing.price(B,g,List.of(11L)).unitPrice()),"full");
    bad(()->OptionPricing.price(B,g,List.of(20L)),"required"); bad(()->OptionPricing.price(B,g,null),"null"); bad(()->OptionPricing.price(B,g,List.of()),"empty");
    var none=OptionPricing.price(B,List.of(),null); ok(none.key().isEmpty()&&none.unitPrice().equals(d("320.00")),"nogroups");
    bad(()->OptionPricing.price(B,g,List.of(10L,11L)),"single>1"); bad(()->OptionPricing.price(B,g,List.of(11L,30L,31L)),"spice>1");
    ok(d("375.00").equals(OptionPricing.price(B,g,List.of(11L,20L,22L)).unitPrice()),"max ok"); bad(()->OptionPricing.price(B,g,List.of(11L,20L,21L,22L)),"max");
    try{OptionPricing.price(B,g,List.of(11L,23L));throw new AssertionError("x");}catch(BadRequestException e){ok(e.getMessage().contains("Papad"),"unavail name");}
    bad(()->OptionPricing.price(B,g,List.of(11L,999L)),"foreign");
    List<Long> withNull=new ArrayList<>(); withNull.add(11L); withNull.add(null); bad(()->OptionPricing.price(B,g,withNull),"nullid");
    var rep=OptionPricing.price(B,g,List.of(11L,20L,20L,20L)); ok(d("350.00").equals(rep.unitPrice())&&rep.chosen().size()==2,"dedupe");
    ok(OptionPricing.price(B,g,List.of(31L,20L,11L)).key().equals("11,20,31"),"key order");
    ok(!OptionPricing.price(B,g,List.of(11L)).key().equals(OptionPricing.price(B,g,List.of(10L)).key()),"keys differ");
    var neg=List.of(new Group(1L,"Size",OptionKind.SINGLE,true,null,List.of(o(1,"Free?","-100.00",true))));
    bad(()->OptionPricing.price(d("100.00"),neg,List.of(1L)),"zero"); bad(()->OptionPricing.price(d("50.00"),neg,List.of(1L)),"negative"); ok(d("1.00").equals(OptionPricing.price(d("101.00"),neg,List.of(1L)).unitPrice()),"min price");
    List<Long> flood=new ArrayList<>(); for(long i=1;i<=31;i++) flood.add(i); bad(()->OptionPricing.price(B,g,flood),"flood");
    var sc=List.of(new Group(1L,"X",OptionKind.MULTI,false,null,List.of(o(1,"a","0.005",true)))); ok(OptionPricing.price(d("10"),sc,List.of(1L)).unitPrice().scale()==2,"scale");
    var ch=OptionPricing.price(B,g,List.of(11L,20L)).chosen().stream().filter(c->c.optionId()==20L).findFirst().orElseThrow(); ok(ch.groupName().equals("Extras")&&ch.optionName().equals("Raita")&&ch.priceDelta().equals(d("30.00")),"snapshot");
    System.out.println("OPTION PRICING: PASSED "+n+" assertions");
  }
}
