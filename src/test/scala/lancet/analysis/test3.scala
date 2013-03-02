package lancet
package analysis

class TestAnalysis3 extends FileDiffSuite {

  val prefix = "test-out/test-analysis-3"

/* 
  putting things together: integers + references 
    (+ equality + dependencies)
*/

  object Test1 {

    type Var = String
    type Addr = String
    type Alloc = String
    type Field = String

    abstract class Val
    case class VInt(x: Int) extends Val {
      override def toString = x.toString
    }
    case class VAddr(x: Addr) extends Val {
      override def toString = x.toString
    }
    case class VUndef() extends Val {
      override def toString = "undef"
    }
    case class VNew(x: Alloc) extends Val {
      override def toString = "new"+x.toString
    }

    case class VRef(x: Var) extends Val {
      override def toString = x
    }
    case class VLess(a: Val, b: Val) extends Val {
      override def toString = a+"<"+b
    }
    case class VPlus(a: Val, b: Val) extends Val {
      override def toString = a+"+"+b
    }
    case class VUpdate(a: Val, f: Field, b: Val) extends Val {
      override def toString = "a["+f+"->"+b+"]"
    }


    case class VIf(c: Val, a: Val, b: Val) extends Val {
      override def toString = "phi("+c+":"+a+","+b+")"
    }
    case class VWhile(c: Val, a: Val, b: Val) extends Val {
      override def toString = "lphi("+c+":"+a+","+b+")"
    }


    def vint(x: Int) = VInt(x)
    def vaddr(x: Addr) = VAddr(x)
    def vundef = VUndef()
    def vnew(x: Alloc) = VNew(x)

    def vref(x: Var) = VRef(x)
    def vless(a: Val, b: Val) = (a,b) match {
      case (VInt(a),VInt(b)) => VInt(if (a < b) 1 else 0)
      case _ => VLess(a,b)
    }
    def vplus(a: Val, b: Val) = (a,b) match {
      case (VInt(a),VInt(b)) => VInt(a+b)
      case _ => VPlus(a,b)
    }
    def vupdate(a: Val, f: Field, b: Val) = VUpdate(a,f,b)

    def vif(c: Val, a: Val, b: Val) =
      if (c == VInt(1)) a else if (c == VInt(0)) b 
      else {
        // resolved nested conditionals by substitution (inefficient, but hey ...)
        // TODO: implication would be nice, too (e.g. for inequalities)
        val u = vsubst(a,c,vint(1))
        val v = vsubst(b,c,vint(0))
        println("--> phi "+c+" "+u+" "+v)
        if (u == v) u
        else VIf(c,u,v)
      }
    def vwhile(c: Val, a: Val, b: Val) = {
      val v = vsubst(b,c,vint(1))
      if (a == v) a else VWhile(c,a,v)
    }


    def vsubst(term: Val, a: Val, b: Val): Val = term match { // a->b in term
      case VLess(x,y) => vless(vsubst(x,a,b),vsubst(y,a,b))
      case VPlus(x,y) => vplus(vsubst(x,a,b),vsubst(y,a,b))
      case VIf(z,x,y) => vif(vsubst(z,a,b),vsubst(x,a,b),vsubst(y,a,b))
      case VWhile(z,x,y) => vwhile(vsubst(z,a,b),vsubst(x,a,b),vsubst(y,a,b))
      case `a` => b
      case _ => term
    }


    abstract class Obj {
      def apply(f: Field): Val = vundef
      def +(x:(Field,Val)): Obj = OUpdate(this,Map(x._1->x._2))
      def ++(xs:(Field,Val)*): Obj = xs.foldLeft(this)(_+_)
    }
    case class OUndef() extends Obj
    case class OStatic(x:Addr) extends Obj {
      //override def apply(f: Field) = vref(x+"."+f)
    }
    case class ORef(x:String) extends Obj {
      override def apply(f: Field) = vref(x+"."+f)
    }
    case class ONew(x:Alloc) extends Obj
    case class OIf(c: Val, a: Obj, b: Obj) extends Obj {
      override def apply(f: Field) = vif(c,a(f),b(f))
    }
    case class OWhile(c: Val, a: Obj, b: Obj) extends Obj
    case class OUpdate(a: Obj, m: Map[Field,Val]) extends Obj {
      override def apply(f: Field) = m.getOrElse(f,a(f))
      override def +(x:(Field,Val)) = OUpdate(a,m+x)
      override def toString = a+"+{" + m.mkString(",") + "}"
    }

    def osubst(term: Obj, a: Val, b: Val): Obj = term match { // a->b in term
      case OUpdate(x,m) => osubst(x,a,b) ++ (m.map {case(k,v)=>(k,vsubst(v,a,b))}.toSeq:_*)
      case OIf(z,x,y) => oif(vsubst(z,a,b),osubst(x,a,b),osubst(y,a,b))
      case OWhile(z,x,y) => owhile(vsubst(z,a,b),osubst(x,a,b),osubst(y,a,b))
      case _ => term
    }

    def oif(c: Val, a: Obj, b: Obj): Obj = (a,b) match {
      case (OUpdate(za,ma),OUpdate(zb,mb)) =>
        val m = (ma.keys ++ mb.keys).map { k => (k, (ma.get(k),mb.get(k)) match {
          case (Some(a),Some(b)) if a == b => a
          case (Some(a),Some(b)) => vif(c, a, b)
          case (Some(a),_) => vif(c, a, vundef)
          case (_,Some(b)) => vif(c, vundef, b)
        })}.toMap
        oif(c,za,zb) ++ (m.toSeq:_*)
      case (a,b) => 
        if (c == vint(1)) a else if (c == vint(0)) b else {
          val (u,v) = (osubst(a,c,vint(1)), osubst(b,c,vint(0)))
          if (u == v) u else OIf(c,u,v)
        }
    }

    def owhile(c: Val, a: Obj, b: Obj): Obj = (a,b) match {
      case (OUpdate(za,ma),OUpdate(zb,mb)) =>
        val m = (ma.keys ++ mb.keys).map { k => (k, (ma.get(k),mb.get(k)) match {
          case (Some(a),Some(b)) if a == b => a
          case (Some(a),Some(b)) => vwhile(c, a, b)
          case (None,Some(b)) => vwhile(c, vundef, b)
          //case (Some(a),_) => a
          //case (_,Some(b)) => b
        })}.toMap
        owhile(c,za,zb) ++ (m.toSeq:_*)
      case (a,b) => if (a == b) a else OWhile(c,a,b)
    }


    case class Store(m: Map[Var,Obj], rec: Map[Var,Obj], factsTrue: Set[Val], factsFalse: Set[Val]) {
      def apply(x:Var): Obj = m.getOrElse(x,OUndef())
      def updated(x:(Var,Obj)): Store = Store(m+x, rec, factsTrue, factsFalse)

      def apply(x:Val): Obj = x match {
        case VAddr(x) => this(x)
        case VIf(c,a,b) => oif(c,this(a),this(b))
      }

      def +(x:(Val,Obj)): Store = x match {
        case (VAddr(x), o) => this updated (x->o)
        case (VIf(c,a,b), o) => this + (a -> oif(c,o,this(a))) + (b -> oif(c,this(b),o))
      }

      override def toString = "env: \n" + m.mkString("\n") + 
        "\nrec: \n" + rec.mkString("\n") +
        "\ntrue: " + factsTrue + "\nfalse: " + factsFalse
    
    }


    def infix_join(a: Store, c: Val, b: Store): Store = {
      val m = (a.m.keys ++ b.m.keys).map { k => (k, (a(k),b(k)) match {
        case (a,b) if a == b => a
        case (a,b) => oif(c, a, b)
        //case (Some(a),_) => a
        //case (_,Some(b)) => b
      })}.toMap
      Store(m, a.rec ++ b.rec, a.factsTrue intersect b.factsTrue, a.factsFalse intersect b.factsFalse)
    }


    def infix_joinFix(a: Store, c: Val, b: Store): Store = { // a previous, b next
      //println("++++ join")
      //println(a)
      //println(b)
      var r = a.rec ++ b.rec // ??
      val m = (a.m.keys ++ b.m.keys).map { k => (k, (a(k),b(k)) match {
        case (a,b) if a == b => a
        case (a,b) => 
          val k0 = k+"0"
          val fix = owhile(c,a,b)
          r = r + (k0->fix); 
          ORef(k0)
      })}.toMap
      Store(m, r, a.factsTrue intersect b.factsTrue, a.factsFalse intersect b.factsFalse)
    }

    def infix_join(a: Val, c: Val, b: Val): Val  = vif(c,a,b)


    def mayZero(a: Val): Boolean = a match {
      //case VLess(VRef(k),VInt(c)) =>
        //println("mayZero "+a)
        //println(store)
        /*store.rec(k) match {
          case VInt(k0) => !(k0 < c) // ok?
          case _ => true
        }*/
      case VInt(c) if c != 0 => 
        false
      case _ => 
        println("default case for mayZero "+a)
        true 
    }
    def mustZero(a: Val): Boolean = false

    def assert(a: Val): Unit = store = Store(store.m, store.rec, store.factsTrue + a, store.factsFalse)
    //def assertNot(a: Val): Unit = store = Store(store.m, store.rec, store.factsTrue, store.factsFalse + a)

    def assertNot(a: Val): Unit = {
      // simplify store mappings after loop has terminated
      val m = store.m.map {
        case (k,OIf(`a`,u,v)) => (k,v)
        case (k,ORef(k0)) => (k, store.rec(k0) match {
          case OUpdate(OStatic(`k`),m) =>
            OUpdate(OStatic(`k`),m map { case (k,v) => 
              val kf = k0+"."+k
              (k, v match {
                // x = high; while (low < x-1) x = x-1    -->    if (low < high-1) low else high
                case VWhile(a1 @ VLess(low,VPlus(VRef(`kf`),VInt(-1))),high,VPlus(VRef(`kf`),VInt(-1))) if a == a1 => 
                  vif(vless(low, vplus(high, vint(-1))), low, high)
                // x = low; while (x+1 < high) x = x+1    -->    if (low+1 < high) high else low
                case VWhile(a1 @ VLess(VPlus(VRef(`kf`),VInt(1)),high),low,VPlus(VRef(`kf`),VInt(1))) if a == a1 => 
                  vif(vless(vplus(low, vint(1)), high), high, low)
                // how to handle 3rd case of testA (nested if)?
                case _ => vref(kf)
              })})
          case o => ORef(k0)
        })
        case (k,v) => (k,v)
      }

      store = Store(m, store.rec, store.factsTrue, store.factsFalse + a)
    }

/*
    def assertNot(a: Val): Unit = {
      // simplify store mappings after loop has terminated
      val m = store.m.map {
        case (k,VIf(`a`,u,v)) => (k,v)
        case (k,VRef(k0)) => (k, store.rec(k0) match {
          // x = high; while (low < x-1) x = x-1    -->    if (low < high-1) low else high
          case VWhile(a1 @ VLess(low,VPlus(VRef(`k0`),VConst(-1))),high,VPlus(VRef(`k0`),VConst(-1))) if a == a1 => 
            vif(vless(low, vplus(high, vconst(-1))), low, high)
          // x = low; while (x+1 < high) x = x+1    -->    if (low+1 < high) high else low
          case VWhile(a1 @ VLess(VPlus(VRef(`k0`),VConst(1)),high),low,VPlus(VRef(`k0`),VConst(1))) if a == a1 => 
            vif(vless(vplus(low, vconst(1)), high), high, low)
          // how to handle 3rd case with nested if?
          case _ => vref(k0)            
        })
        case (k,v) => (k,v)
      }

      store = Store(m, store.rec, store.factsTrue, store.factsFalse + a)
    }
*/

    val store0: Store = Store(Map.empty, Map.empty, Set.empty, Set.empty)
    var store: Store = _


    abstract class Exp
    case class Const(x: Int) extends Exp
    case class Direct(x: Val) extends Exp
    case class Ref(x: Var) extends Exp
    case class Assign(x: Var, y: Exp) extends Exp
    case class Plus(x: Exp, y: Exp) extends Exp
    case class Less(x: Exp, y: Exp) extends Exp
    case class New(x: Alloc) extends Exp
    case class Get(x: Exp, f: Field) extends Exp
    case class Put(x: Exp, f: Field, y: Exp) extends Exp
    case class If(c: Exp, a: Exp, b: Exp) extends Exp
    case class While(c: Exp, b: Exp) extends Exp
    case class Block(xs: List[Exp]) extends Exp {
      override def toString = "{\n  " + xs.map(_.toString).mkString("\n").replace("\n","\n  ") + "\n}"
    }

    def freshAddr(x:Alloc) = VAddr("alloc"+x)

    def eval(e: Exp): Val = e match {
      case Const(x) => vint(x)
      case Direct(x) => x
      case Ref(x) => store("&"+x)("val")
      case Assign(x,y) => 
        val y1 = eval(y)
        store = store + (VAddr("&"+x) -> (OStatic("&"+x) + ("val" -> y1))); vundef
      case Plus(x,y) => vplus(eval(x),eval(y))
      case Less(x,y) => vless(eval(x),eval(y))
      case New(x) => 
        val a = freshAddr(x)
        //store = store + (a -> OFlat(Map.empty))
        val key = if (store(a) == OUndef()) x else x+"_loop"
        store = store + (a -> ONew(key))
        //println("about to "+e+"/"+a)
        //println(store)
        a
      case Get(x, f) => 
        val a = eval(x)
        store(a)(f)
      case Put(x, f, y) => 
        val a = eval(x)
        val y1 = eval(y)
        //println("about to "+e+"/"+a+","+y1)
        //println(store)
        val x1 = store(a) //.getOrElse(a, UndefinedObj())  assert it's defined?
        store = store + (a -> (x1 + (f -> y1)))
        vint(0)
      case If(c,a,b) => 
        val c1 = eval(c)
        if (!mayZero(c1)) eval(a) else if (mustZero(c1)) eval(b) else {
          val save = store
          assert(c1)
          val e1 = eval(a)
          val s1 = store
          store = save
          assertNot(c1)
          val e2 = eval(b)
          val s2 = store
          store = s1 join (c1,s2)
          e1 join (c1,e2)
        }
      case While(c,b) => 

        val c0 = eval(c)
        val sBefore0 = store
        assert(c0)
        eval(b)
        val c1 = eval(c)
        val sAfter0 = store
        val sBefore1 = sBefore0 joinFix (c1,sAfter0)
        store = sBefore1
        assert(c1)
        eval(b)
        val c2 = eval(c)
        val sAfter1 = store
        val sBefore2 = sBefore0 joinFix (c2,sAfter1)
        store = sBefore2
        assert(c2)
        eval(b)
        val c3 = eval(c)
        val sAfter2 = store
        val sBefore3 = sBefore0 joinFix (c3,sAfter2)
        store = sBefore3
        assertNot(c3)
        vundef
        // TODO: fixpoint!
      case Block(Nil) => vundef
      case Block(xs) => xs map eval reduceLeft ((a,b) => b)
    }

    // codegen

    //def emit()





    def run(testProg: Exp) = {
      println("prog: " + testProg)
      store = store0
      val res = eval(testProg)
      println("res: " + res)
      println(store)
      //store.printBounds
      println("----")
    }

    // test some integer computations

    val testProg1 = Block(List(
      Assign("i", Const(0)),
      Assign("y", Const(0)),
      Assign("x", Const(8)),
      While(Less(Ref("i"),Const(100)), Block(List(
        Assign("x", Const(7)),
        Assign("x", Plus(Ref("x"), Const(1))),
        Assign("y", Plus(Ref("y"), Const(1))), // TOOD: how to relate to loop var??
        Assign("i", Plus(Ref("i"), Const(1)))
      )))
    ))

    val testProg2 = Block(List(
      Assign("x", Const(900)), // input
      Assign("y", Const(0)),
      Assign("z", Const(0)),
      While(Less(Const(0), Ref("x")), Block(List(
        Assign("z", Plus(Ref("z"), Ref("x"))),
        If(Less(Ref("y"),Const(17)), 
          Block(List(
            Assign("y", Plus(Ref("y"), Const(1)))
          )),
          Block(Nil)
        ),
        Assign("x", Plus(Ref("x"), Const(-1)))
      ))),
      Assign("r", Ref("x"))
    ))

    // test store logic

    val testProg3 = Block(List(
      Assign("i", Const(0)),
      Assign("z", New("A")),
      Assign("x", Ref("z")),
      While(Less(Ref("i"),Const(100)), Block(List(
        Assign("y", New("B")),
        Put(Ref("y"), "head", Ref("i")),
        Put(Ref("y"), "tail", Ref("x")),
        Assign("x", Ref("y")),
        Assign("i", Plus(Ref("i"), Const(1)))
      )))
    ))

    val testProg4 = Block(List(
      Assign("i", Const(0)),
      Assign("z", New("A")),
      Assign("x", Ref("z")),
      Assign("y", New("B")),
      While(Less(Ref("i"),Const(100)), Block(List(
        Put(Ref("y"), "head", Ref("i")),
        Put(Ref("y"), "tail", Ref("x")),
        Assign("x", Ref("y")),
        Assign("i", Plus(Ref("i"), Const(1)))
      )))
    ))

    val testProg5 = Block(List(
      Assign("i", Const(0)),
      Assign("z", New("A")),
      Assign("x", Ref("z")),
      While(Less(Ref("i"),Const(100)), Block(List(
        Put(Ref("x"), "head", Ref("i")),
        Assign("i", Plus(Ref("i"), Const(1)))
      )))
    ))

    // modify stuff after a loop

    val testProg6 = Block(List(
      Assign("i", Const(0)),
      Assign("z", New("A")),
      Assign("x", Ref("z")),
      Assign("y", New("B")),
      While(Less(Ref("i"),Const(100)), Block(List(
        Put(Ref("y"), "head", Ref("i")),
        Put(Ref("y"), "tail", Ref("x")),
        Assign("x", Ref("y")),
        Assign("i", Plus(Ref("i"), Const(1)))
      ))),
      Put(Ref("y"), "tail", Ref("z")),
      Put(Ref("y"), "head", Const(7))
    ))

    // strong update for if

    val testProg7 = Block(List(
      Assign("x", New("A")),
      If(Direct(vref("input")),
        Block(List(
          Put(Ref("x"), "a", New("B")),
          Put(Get(Ref("x"), "a"), "foo", Const(5))
        )),
        Block(List(
          Put(Ref("x"), "a", New("C")),
          Put(Get(Ref("x"), "a"), "bar", Const(5))
        ))
      ),
      Assign("foo", Get(Get(Ref("x"), "a"), "foo")),
      Assign("bar", Get(Get(Ref("x"), "a"), "bar"))
    ))

    val testProg8 = Block(List(
      //Put(Static(0), "counter", Const(1)),
      Assign("x", New("A")),
      Put(Ref("x"), "a", New("A2")),
      Put(Get(Ref("x"), "a"), "baz", Const(3)),
      If(Direct(vref("input")),
        Block(List(
          Put(Ref("x"), "a", New("B")), // strong update, overwrite
          Put(Get(Ref("x"), "a"), "foo", Const(5))
        )),
        Block(List(
          Put(Ref("x"), "a", New("C")), // strong update, overwrite
          Put(Get(Ref("x"), "a"), "bar", Const(5))
        ))
      ),
      Put(Get(Ref("x"), "a"), "bar", Const(7)), // this is not a strong update, because 1.a may be one of two allocs
      Assign("xbar", Get(Get(Ref("x"), "a"), "bar")) // should still yield 7!
    ))

  }

  def testA = withOutFileChecked(prefix+"A") {
    Test1.run(Test1.testProg1)
    Test1.run(Test1.testProg2)
  }

  def testB = withOutFileChecked(prefix+"B") {
    Test1.run(Test1.testProg3)
    Test1.run(Test1.testProg4) // 3 and 4 should be different: alloc within the loop vs before
    Test1.run(Test1.testProg5)
  }
  def testC = withOutFileChecked(prefix+"C") {
    Test1.run(Test1.testProg6)
  }
  def testD = withOutFileChecked(prefix+"D") {
    Test1.run(Test1.testProg7)
    Test1.run(Test1.testProg8)
  }



}