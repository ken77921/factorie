package cc.factorie.app.nlp.coref
import cc.factorie._
import com.mongodb.DB
import db.mongo._
import la.SparseIndexedTensor
import collection.mutable.{ArrayBuffer,ListBuffer,HashSet,HashMap,LinkedHashMap}

class BagOfTruths(val entity:Entity, truths:Map[String,Double]=null) extends BagOfWordsVariable(Nil,truths) with EntityAttr
class EntityExists(val entity:Entity,initialValue:Boolean) extends BooleanVariable(initialValue)
class IsEntity(val entity:Entity,initialValue:Boolean) extends BooleanVariable(initialValue)
class IsMention(val entity:Entity,initialValue:Boolean) extends BooleanVariable(initialValue)
class Dirty(val entity:Entity) extends IntegerVariable(0){def reset()(implicit d:DiffList):Unit=this.set(0)(d);def ++()(implicit d:DiffList):Unit=this.set(intValue+1)(d);def --()(implicit d:DiffList):Unit=this.set(intValue-1)(d)} //convenient for determining whether an entity needs its attributes recomputed
class MentionCountVariable(val entity:Entity,initialValue:Int=0) extends IntegerVariable(initialValue)


abstract class HierEntity(isMent:Boolean=false) extends Entity{
  isObserved=isMent
  var groundTruth:Option[String] = None
  val bagOfTruths = new BagOfTruths(this)    
  def flagAsMention:Unit = {isObserved=true;isMention.set(true)(null);numMentionsInSubtree.set(1)(null)}
  def isEntity = attr[IsEntity]
  def isMention = attr[IsMention]
  def exists = attr[EntityExists]
  def dirty = attr[Dirty]
  def numMentionsInSubtree = attr[MentionCountVariable]
  attr += new EntityExists(this,this.isConnected)
  attr += new IsEntity(this,this.isRoot)
  attr += new IsMention(this,this.isObserved)
  attr += new Dirty(this)
  attr += bagOfTruths
  if(this.isObserved)attr += new MentionCountVariable(this,1) else attr += new MentionCountVariable(this,0)
  override def removedChildHook(entity:Entity)(implicit d:DiffList)={super.removedChildHook(entity);exists.set(this.isConnected)(d);dirty++}
  override def addedChildHook(entity:Entity)(implicit d:DiffList)={super.addedChildHook(entity);exists.set(this.isConnected)(d);dirty++}
  override def changedParentEntityHook(oldEntity:Entity,newEntity:Entity)(implicit d:DiffList){super.changedParentEntityHook(oldEntity,newEntity);isEntity.set(this.isRoot)(d);exists.set(this.isConnected)(d)}
}

abstract class HierEntityCubbie extends EntityCubbie{
  val isMention = BooleanSlot("isMention")
  override def finishFetchEntity(e:Entity):Unit ={
    e.attr[IsMention].set(isMention.value)(null)
    e.isObserved=isMention.value
    e.attr[IsEntity].set(e.isRoot)(null)
    e.attr[EntityExists].set(e.isConnected)(null)
  }
  override def finishStoreEntity(e:Entity):Unit ={
    isMention := e.attr[IsMention].booleanValue
  }
}
trait Prioritizable{
  var priority:Double=0.0
}
trait HasCanopyAttributes[T<:Entity]{
  val canopyAttributes = new ArrayBuffer[CanopyAttribute[T]]
}
trait CanopyAttribute[T<:Entity]{def entity:Entity;def canopyName:String}
class SimpleStringCanopy[T<:Entity](val entity:T,val canopyName:String) extends CanopyAttribute[T]
/**Mix this into the sampler and it will automatically propagate the bags that you define in the appropriate method*/
/*
trait AutomaticBagPropagation{
  def bagsToPropgate(e:Entity):Seq[BagOfWordsVariable]
}
*/

abstract class HierCorefSampler[T<:HierEntity](model:Model) extends SettingsSampler[Null](model, null) {
  def timeAndProcess(n:Int):Unit = super.process(n) //temporary fix to no longer being able to override process.
  def newEntity:T
  //def reestimateAttributes(e:T):Unit 
  protected var entities:ArrayBuffer[T] = null
  protected var deletedEntities:ArrayBuffer[T] = null
  def getEntities:Seq[T] = entities.filter(_.isConnected)
  def getDeletedEntities = {
    val deleted = new HashSet[T]
    for(d<-deletedEntities)deleted += d
    for(d<-entities)if(!d.isConnected)deleted += d
    //performMaintenance(entities)
    //deletedEntities
    deleted.toSeq
  }
  def infer(numSamples:Int):Unit ={}
  /**Returns a random entity that 'exists'*/
  //def nextEntity:T = nextEntity(null.asInstanceOf[T])
  def nextEntity:T=nextEntity(null.asInstanceOf[T])
  def nextEntity(context:T):T=sampleEntity(entities)
  def nextEntityPair:(T,T) = {
    val e1 = nextEntity
    val e2 = nextEntity(e1)
    (e1,e2)
  }
  def sampleAttributes(e:T)(implicit d:DiffList):Unit //= {e.dirty.reset}

  protected def sampleEntity(samplePool:ArrayBuffer[T]) = {
    val initialSize = samplePool.size
    var tries = 5
    var e:T = null.asInstanceOf[T]
    while({tries-=1;tries} >= 0 && (e==null || !e.isConnected) && samplePool.size>0){
      e = samplePool(random.nextInt(samplePool.size))
      if(tries==1)performMaintenance(samplePool)
    }
    //if(e!=null && !e.isConnected)throw new Exception("NOT CONNECTED")
    if(e!= null && !e.isConnected)e==null.asInstanceOf[T]
    e
  }
  def setEntities(ents:Iterable[T]) = {entities = new ArrayBuffer[T];for(e<-ents)addEntity(e);deletedEntities = new ArrayBuffer[T]}
  /**Garbage collects all the deleted entities from the master list of entities*/
  def performMaintenance(es:ArrayBuffer[T]):Unit ={
    //println("Performing maintenance")
    var oldSize = es.size
    val cleanEntities = new ArrayBuffer[T]
    cleanEntities ++= es.filter(_.isConnected)
    deletedEntities ++= es.filter(!_.isConnected)
    es.clear
    es++=cleanEntities
    //println("  removed "+(oldSize-es.size)+ " disconnected entities. new size:"+es.size)
  }
  //def newDiffList2 = new cc.factorie.example.DebugDiffList
  /**This function randomly generates a list of jumps/proposals to choose from.*/
  def settings(c:Null) : SettingIterator = new SettingIterator {
    val changes = new scala.collection.mutable.ArrayBuffer[(DiffList)=>Unit]
    val (entity1,entity2) = nextEntityPair
    if (entity1.entityRoot.id != entity2.entityRoot.id) { //sampled nodes refer to different entities
      if(!isMention(entity1)){
        changes += {(d:DiffList) => mergeLeft(entity1,entity2)(d)} //what if entity2 is a mention?
        if(entity1.id != entity1.entityRoot.id) //avoid adding the same jump to the list twice
          changes += {(d:DiffList) => mergeLeft(entity1.entityRoot.asInstanceOf[T],entity2)(d)} //unfortunately casting is necessary unless we want to type entityRef/parentEntity/childEntities
      }
      if(entity1.parentEntity==null && entity2.parentEntity==null)
        changes += {(d:DiffList) => mergeUp(entity1,entity2)(d)}
      else
        changes += {(d:DiffList) => mergeUp(entity1.entityRoot.asInstanceOf[T],entity2.entityRoot.asInstanceOf[T])(d)}
    } else { //sampled nodes refer to same entity
      changes += {(d:DiffList) => splitRight(entity1,entity2)(d)}
      changes += {(d:DiffList) => splitRight(entity2,entity1)(d)}
      if(entity1.parentEntity != null && !entity1.isObserved)
        changes += {(d:DiffList) => {collapse(entity1)(d)}}
    }
    if(entity1.dirty.value>0 && !entity1.isObserved)changes += {(d:DiffList) => sampleAttributes(entity1)(d)}
    if(entity1.entityRoot.id != entity1.id && entity1.entityRoot.attr[Dirty].value>0 && !entity1.entityRoot.isObserved)changes += {(d:DiffList) => sampleAttributes(entity1.entityRoot.asInstanceOf[T])(d)}

    changes += {(d:DiffList) => {}} //give the sampler an option to reject all other proposals
    var i = 0
    def hasNext = i < changes.length
    def next(d:DiffList) = {val d = newDiffList; changes(i).apply(d); i += 1; d }
    def reset = i = 0
  }
  /**Removes an intermediate node in the tree, merging that nodes children to their grandparent.*/
  def collapse(entity:T)(implicit d:DiffList):Unit ={
    if(entity.parentEntity==null)throw new Exception("Can't collapse a node that is the root of a tree.")
    val root = entity.entityRoot
    //println("ROOT1:"+cc.factorie.example.Coref3.entityString(root))
    //println("entity:"+cc.factorie.example.Coref3.entityString(entity))
    //println("checking 1")
    //cc.factorie.example.Coref3.checkIntegrity(entity)
    val oldParent = entity.parentEntity
    //entity.childEntitiesIterator.foreach(_.setParentEntity(entity.parentEntity)(d))
    //println("  num children: "+entity.childEntitiesSize)
//    val childrenCopy = new ArrayBuffer[Entity];childrenCopy ++= entity.childEntitiesIterator
//    for(child <- childrenCopy)child.setParentEntity(entity.parentEntity)(d)
    for(child <- entity.safeChildEntitiesSeq)child.setParentEntity(entity.parentEntity)(d)
    //println("checking 2")
    //cc.factorie.example.Coref3.checkIntegrity(entity)
    entity.setParentEntity(null)(d)
    //println("checking 3")
    //cc.factorie.example.Coref3.checkIntegrity(root)
    //println("ROOT3:"+cc.factorie.example.Coref3.entityString(root))

  }
  /**Peels off the entity "right", does not really need both arguments unless we want to error check.*/
  def splitRight(left:T,right:T)(implicit d:DiffList):Unit ={
    val oldParent = right.parentEntity
    right.setParentEntity(null)(d)
    structurePreservationForEntityThatLostChild(oldParent)(d)
  }
  /**Jump function that proposes merge: entity1<----entity2*/
  def mergeLeft(entity1:T,entity2:T)(implicit d:DiffList):Unit ={
    val oldParent = entity2.parentEntity
    entity2.setParentEntity(entity1)(d)
    structurePreservationForEntityThatLostChild(oldParent)(d)
  }
  /**Jump function that proposes merge: entity1--->NEW-PARENT-ENTITY<---entity2 */
  def mergeUp(e1:T,e2:T)(implicit d:DiffList):T = {
    val oldParent1 = e1.parentEntity
    val oldParent2 = e2.parentEntity
    val result = newEntity
    e1.setParentEntity(result)(d)
    e2.setParentEntity(result)(d)
    structurePreservationForEntityThatLostChild(oldParent1)(d)
    structurePreservationForEntityThatLostChild(oldParent2)(d)
    result
  }
  /**Ensure that chains are not created in our tree. No dangling children-entities either.*/
  protected def structurePreservationForEntityThatLostChild(e:Entity)(implicit d:DiffList):Unit ={
    if(e!=null && e.childEntitiesSize<=1){
      for(childEntity <- e.childEntities)
        childEntity.setParentEntity(e.parentEntity)
      e.setParentEntity(null)(d)
    }
  }
  /**Identify entities that are created by accepted jumps so we can add them to our master entity list.*/
  override def proposalHook(proposal:Proposal) = {
    super.proposalHook(proposal)
    val newEntities = new HashSet[T]
    for(diff<-proposal.diff){
      diff.variable match {
        case v:EntityExists => diff.undo
        case v:IsEntity => diff.undo
        case _ => {}
      }
    }
    for(diff<-proposal.diff){
      diff.variable match{
        case children:ChildEntities => if(!children.entity.attr[EntityExists].booleanValue && !children.entity.isObserved)newEntities += children.entity.asInstanceOf[T] //cast could be avoided if children entities were typed
        case _ => {}
      }
    }
    for(diff<-proposal.diff){
      diff.variable match{
        case v:EntityExists => diff.redo
        case v:IsEntity => diff.redo
        case _ => {}
      }
    }
    /*
    //undo all vars but bags of words (they are expensive to undo)
    /*
    for(diff<-proposal.diff){
      diff.variable match {
        case d:BagOfWordsVariable => {}
        case _ => diff.undo
      }
    }*/
    proposal.diff.undo //an entity that does not exit in the current world is one that was newly created by the jump
    for(diff<-proposal.diff){
      diff.variable match{
        case children:ChildEntities => if(!children.entity.isConnected && !children.entity.isObserved)newEntities += children.entity.asInstanceOf[T] //cast could be avoided if children entities were typed
        case _ => {}
      }
    }
    /*
    //redo vars that had been undone
    for(diff<-proposal.diff){
      diff.variable match {
        case d:BagOfWordsVariable => {}
        case _ => diff.redo
      }
    }
    */
    proposal.diff.redo
    */
    for(entity<-newEntities)addEntity(entity)
  }
  def addEntity(e:T):Unit ={entities += e}
  def isMention(e:Entity):Boolean = e.isObserved
}


/*
abstract class FastTemplate1[N1<:Variable](implicit nm1: Manifest[N1]) extends Template1[N1]()(nm1){
//  override def factorsWithDuplicates(v:Variable): Iterable[FactorType] = {
//    // TODO Given the surprise about how slow Manifest <:< was, I wonder how slow this is when there are lots of traits!
//    // When I substituted "isAssignable" for HashMap caching in GenericSampler I got 42.8 versus 44.4 seconds ~ 3.7%  Perhaps worth considering?
//    val ret = new ListBuffer[FactorType]
//    // Create Factor iff variable class matches and the variable domain matches
//    if (neighborClass1.isAssignableFrom(v.getClass) && ((neighborDomain1 eq null) || (neighborDomain1 eq v.domain))) ret ++= unroll1(v.asInstanceOf[N1])
//    if ((neighborClass1a ne null) && neighborClass1a.isAssignableFrom(v.getClass)) ret ++= unroll1s(v.asInstanceOf[N1#ContainedVariableType])
//    // TODO It would be so easy for the user to define Variable.unrollCascade to cause infinite recursion.  Can we make better checks for this?
//    //val cascadeVariables = unrollCascade(v); if (cascadeVariables.size > 0) ret ++= cascadeVariables.flatMap(factorsWithDuplicates(_))
//    ret
//  }

}

abstract class FastTemplate3[N1<:Variable,N2<:Variable,N3<:Variable](implicit nm1:Manifest[N1], nm2:Manifest[N2], nm3:Manifest[N3]) extends Template3[N1,N2,N3]()(nm1,nm2,nm3){
<<<<<<< local
  override def factorsWithDuplicates(v: Variable): Iterable[FactorType] = {
    val ret = new ListBuffer[FactorType]
    if (neighborClass1.isAssignableFrom(v.getClass) && ((neighborDomain1 eq null) || (neighborDomain1 eq v.domain))) ret ++= unroll1(v.asInstanceOf[N1])
    if (neighborClass2.isAssignableFrom(v.getClass) && ((neighborDomain2 eq null) || (neighborDomain2 eq v.domain))) ret ++= unroll2(v.asInstanceOf[N2])
    if (neighborClass3.isAssignableFrom(v.getClass) && ((neighborDomain3 eq null) || (neighborDomain3 eq v.domain))) ret ++= unroll3(v.asInstanceOf[N3])
    if ((nc1a ne null) && nc1a.isAssignableFrom(v.getClass)) ret ++= unroll1s(v.asInstanceOf[N1#ContainedVariableType])
    if ((nc2a ne null) && nc2a.isAssignableFrom(v.getClass)) ret ++= unroll2s(v.asInstanceOf[N2#ContainedVariableType])
    if ((nc3a ne null) && nc3a.isAssignableFrom(v.getClass)) ret ++= unroll3s(v.asInstanceOf[N3#ContainedVariableType])
    //val cascadeVariables = unrollCascade(v); if (cascadeVariables.size > 0) {throw Exception("Error")}//ret ++= cascadeVariables.flatMap(factorsWithDuplicates(_))}
    ret
  }
=======
//    override def factorsWithDuplicates(v: Variable): Iterable[FactorType] = {
//    val ret = new ListBuffer[FactorType]
//    if (neighborClass1.isAssignableFrom(v.getClass) && ((neighborDomain1 eq null) || (neighborDomain1 eq v.domain))) ret ++= unroll1(v.asInstanceOf[N1])
//    if (neighborClass2.isAssignableFrom(v.getClass) && ((neighborDomain2 eq null) || (neighborDomain2 eq v.domain))) ret ++= unroll2(v.asInstanceOf[N2])
//    if (neighborClass3.isAssignableFrom(v.getClass) && ((neighborDomain3 eq null) || (neighborDomain3 eq v.domain))) ret ++= unroll3(v.asInstanceOf[N3])
//    if ((nc1a ne null) && nc1a.isAssignableFrom(v.getClass)) ret ++= unroll1s(v.asInstanceOf[N1#ContainedVariableType])
//    if ((nc2a ne null) && nc2a.isAssignableFrom(v.getClass)) ret ++= unroll2s(v.asInstanceOf[N2#ContainedVariableType])
//    if ((nc3a ne null) && nc3a.isAssignableFrom(v.getClass)) ret ++= unroll3s(v.asInstanceOf[N3#ContainedVariableType])
//    //val cascadeVariables = unrollCascade(v); if (cascadeVariables.size > 0) {throw Exception("Error")}//ret ++= cascadeVariables.flatMap(factorsWithDuplicates(_))}
//    ret
//  }
>>>>>>> other
}
<<<<<<< local
abstract class FastTemplateWithStatistics3[N1<:Variable,N2<:Variable,N3<:Variable](implicit nm1:Manifest[N1], nm2:Manifest[N2], nm3:Manifest[N3]) extends FastTemplate3[N1,N2,N3] with Statistics3[N1#Value,N2#Value,N3#Value] {
  def statistics(value1:N1#Value, value2:N2#Value, value3:N3#Value): StatisticsType = Stat(value1, value2, value3)
=======
abstract class FastTemplateWithStatistics3[N1<:Variable,N2<:Variable,N3<:Variable](implicit nm1:Manifest[N1], nm2:Manifest[N2], nm3:Manifest[N3]) extends   v[N1,N2,N3] {
  //def statistics(value1:N1#Value, value2:N2#Value, value3:N3#Value): StatisticsType = (value1, value2, value3)
>>>>>>> other
}
abstract class FastTemplateWithStatistics1[N1<:Variable](implicit nm1:Manifest[N1]) extends TupleTemplateWithStatistics1[N1] {
  //def statistics(value1:N1#Value): StatisticsType = Statistics(value1)
}
*/

/*
      // Pairwise affinity factor between Mentions in the same partition
      model += new DotTemplate4[EntityRef,EntityRef,Mention,Mention] {
        //def statisticsDomains = Tuple1(AffinityVectorDomain)
        lazy val weights = new la.DenseTensor1(AffinityVectorDomain.dimensionSize)
        def unroll1 (er:EntityRef) = for (other <- er.value.mentions; if (other.entityRef.value == er.value)) yield
          if (er.mention.hashCode > other.hashCode) Factor(er, other.entityRef, er.mention, other.entityRef.mention)
          else Factor(er, other.entityRef, other.entityRef.mention, er.mention)
        def unroll2 (er:EntityRef) = Nil // symmetric
        def unroll3 (mention:Mention) = throw new Error
        def unroll4 (mention:Mention) = throw new Error
        def statistics (e1:EntityRef#Value, e2:EntityRef#Value, m1:Mention#Value, m2:Mention#Value) = new AffinityVector(m1, m2).value
      }

  class AffinityVector(s1:String, s2:String) extends BinaryFeatureVectorVariable[String] {
    import AffinityDomain._
    def domain = AffinityVectorDomain
    if (s1 equals s2) this += streq else this += nstreq
    if (s1.substring(0,1) equals s2.substring(0,1)) this += prefix1 else this += nprefix1
    if (s1.substring(0,2) equals s2.substring(0,2)) this += prefix2 else this += nprefix2
    if (s1.substring(0,3) equals s2.substring(0,3)) this += prefix3 else this += nprefix3
    if (s1.contains(s2) || s2.contains(s1)) this += substring else this += nsubstring
    if (s1.length == s2.length) this += lengtheq
    s1.split(" ").foreach(s => if (s2.contains(s)) this += containsword)
    s2.split(" ").foreach(s => if (s1.contains(s)) this += containsword)
    // Also consider caching mechanisms
  }
  object AffinityDomain extends EnumDomain {
    val streq, nstreq, prefix1, nprefix1, prefix2, nprefix2, prefix3, nprefix3, substring, nsubstring, lengtheq, containsword = Value
  }
  object AffinityVectorDomain extends CategoricalTensorDomain[String] {
    override lazy val dimensionDomain = AffinityDomain
  }

 */

class ChildParentCosineDistance[B<:BagOfWordsVariable with EntityAttr](val weight:Double = 4.0, val shift:Double = -0.25)(implicit m:Manifest[B]) extends ChildParentTemplateWithStatistics[B] with DebugableTemplate{
  val name = "ChildParentCosineDistance(weight="+weight+" shift="+shift+")"
  println("ChildParentCosineDistance: weight="+weight+" shift="+shift)
    override def unroll2(childBow:B) = Nil //note: this is a slight approximation for efficiency
    override def unroll3(childBow:B) = Nil //note this is a slight approximation for efficiency
    def score(er:EntityRef#Value, childBow:B#Value, parentBow:B#Value): Double = {
      //val childBow = s._2
      //val parentBow = s._3
      var result = childBow.cosineSimilarity(parentBow,childBow)
      result = (result+shift)*weight * scala.math.log(scala.math.min(parentBow.l1Norm,childBow.l1Norm)+2)
      if(_debug)println("  "+debug(result))
      result
    }
}
/*
abstract class ChildParentTemplateWithStatistics[A<:EntityAttr](implicit m:Manifest[A]) extends TupleTemplateWithStatistics3[EntityRef,A,A] {
  def unroll1(er:EntityRef): Iterable[Factor] = if(er.dst!=null)Factor(er, er.src.attr[A], er.dst.attr[A]) else Nil
  def unroll2(childAttr:A): Iterable[Factor] = if(childAttr.entity.parentEntity!=null)Factor(childAttr.entity.parentEntityRef, childAttr, childAttr.entity.parentEntity.attr[A]) else Nil
  def unroll3(parentAttr:A): Iterable[Factor] = for(e<-parentAttr.entity.childEntities) yield Factor(e.parentEntityRef,e.attr[A],parentAttr)
}
*/

/*
  /** A feature vector variable measuring affinity between two mentions */
  class AffinityVector(s1:String, s2:String) extends BinaryFeatureVectorVariable[String] {
    import AffinityDomain._
    def domain = AffinityVectorDomain
    if (s1 equals s2) this += streq else this += nstreq
    if (s1.substring(0,1) equals s2.substring(0,1)) this += prefix1 else this += nprefix1
    if (s1.substring(0,2) equals s2.substring(0,2)) this += prefix2 else this += nprefix2
    if (s1.substring(0,3) equals s2.substring(0,3)) this += prefix3 else this += nprefix3
    if (s1.contains(s2) || s2.contains(s1)) this += substring else this += nsubstring
    if (s1.length == s2.length) this += lengtheq
    s1.split(" ").foreach(s => if (s2.contains(s)) this += containsword)
    s2.split(" ").foreach(s => if (s1.contains(s)) this += containsword)
    // Also consider caching mechanisms
  }
  object AffinityDomain extends EnumDomain {
    val streq, nstreq, prefix1, nprefix1, prefix2, nprefix2, prefix3, nprefix3, substring, nsubstring, lengtheq, containsword = Value
  }
  object AffinityVectorDomain extends CategoricalTensorDomain[String] {
    override lazy val dimensionDomain = AffinityDomain
  }
  object TokenFeaturesDomain extends CategoricalTensorDomain[String]
  class TokenFeatures(val token:Token) extends BinaryFeatureVectorVariable[String] {
    def domain = TokenFeaturesDomain
  }
  val model = new CombinedModel(

  this += new ChildParentTemplate[BagOfCoAuthors] with DotStatistics1[AuthorFeatureVector#Value]{
    val name="cpt-bag-coauth-"
    override def statisticsDomains = List(CorefDimensionDomain)
    //override def unroll1(er:EntityRef) = if(er.dst!=null)Factor(er, er.src.attr[BagOfCoAuthors], er.dst.entityRoot.attr[BagOfCoAuthors]) else Nil
    override def unroll2(childBow:BagOfCoAuthors) = Nil
    override def unroll3(parentBow:BagOfCoAuthors) = Nil
    //def statistics (values:Values) = Stat(new AffinityVector(values._2, values._3).value)
    def statistics(values:Values) = {
      val features = new AuthorFeatureVector
      val childBow = values._2
      val parentBow = values._3
      //val dot = childBow.deductedDot(parentBow,childBow)
      //if(dot == 0.0)
      //  features.update(name+"intersect=empty",childBow.l2Norm*parentBow.l2Norm - 0.25)
      if(childBow.size>0 && parentBow.size>0){
        val cossim=childBow.cosineSimilarity(parentBow,childBow)
        val binNames = FeatureUtils.bin(cossim,name+"cosine")
        for(binName <- binNames){
          features.update(binName,1.0)
          //if(parentBow.l1Norm==2)features.update(binName+"ments=2",1.0)
          //if(parentBow.l1Norm>=2)features.update(binName+"ments>=2",1.0)
          //if(parentBow.l1Norm>=4)features.update(binName+"ments>=4",1.0)
          //if(parentBow.l1Norm>=8)features.update(binName+"ments>=8",1.0)
        }
        //if(dot>0)features.update(name+"dot>0",1.0) else features.update(name+"dot=0",1.0)
      }
      //features.update(name+"cosine-no-shift",cossim)
      //features.update(name+"cossine-shifted",cossim-0.5)
      Stat(features.value)
    }
  }
 */

trait DebugableTemplate{
  protected var _debug:Boolean=false
  def debugOn = _debug = true
  def debugOff = _debug = false
  def name:String
  def debug(score:Double):String = score+" ("+name+")"
}

abstract class ChildParentTemplateWithStatistics[A<:EntityAttr](implicit m:Manifest[A]) extends ChildParentTemplate[A] with TupleFamilyWithStatistics3[EntityRef,A,A]
abstract class ChildParentTemplate[A<:EntityAttr](implicit m:Manifest[A]) extends Template3[EntityRef,A,A] {
  def unroll1(er:EntityRef): Iterable[Factor] = if(er.dst!=null)Factor(er, er.src.attr[A], er.dst.attr[A]) else Nil
  def unroll2(childAttr:A): Iterable[Factor] = if(childAttr.entity.parentEntity!=null)Factor(childAttr.entity.parentEntityRef, childAttr, childAttr.entity.parentEntity.attr[A]) else Nil
  def unroll3(parentAttr:A): Iterable[Factor] = for(e<-parentAttr.entity.childEntities) yield Factor(e.parentEntityRef,e.attr[A],parentAttr)
}
class StructuralPriorsTemplate(val entityExistenceCost:Double=2.0,subEntityExistenceCost:Double=0.5) extends TupleTemplateWithStatistics3[EntityExists,IsEntity,IsMention] with DebugableTemplate{
  val name = "StructuralPriorsTemplate(entityCost="+entityExistenceCost+", subEntityCost="+subEntityExistenceCost+")"
  println("StructuralPriorsTemplate("+entityExistenceCost+","+subEntityExistenceCost+")")
  def unroll1(exists:EntityExists) = Factor(exists,exists.entity.attr[IsEntity],exists.entity.attr[IsMention])
  def unroll2(isEntity:IsEntity) = Factor(isEntity.entity.attr[EntityExists],isEntity,isEntity.entity.attr[IsMention])
  def unroll3(isMention:IsMention) = throw new Exception("An entitie's status as a mention should never change.")
  def score(entityExists:EntityExists#Value, isEntityValue:IsEntity#Value, isMentionValue:IsMention#Value):Double ={
    val exists:Boolean = entityExists.booleanValue
    val isEntity:Boolean = isEntityValue.booleanValue
    val isMention:Boolean = isMentionValue.booleanValue
    var result = 0.0
    if(exists && isEntity) result -= entityExistenceCost
    if(exists && !isEntity && !isMention)result -= subEntityExistenceCost
    if(_debug)println("  "+debug(result))
    result
  }
}
class EntropyBagOfWordsPriorWithStatistics[B<:BagOfWordsVariable with EntityAttr](val weight:Double=1.0)(implicit m:Manifest[B]) extends TupleTemplateWithStatistics3[EntityExists,IsEntity,B] with DebugableTemplate{
  val name = "EntropyBagOfWordsPriorWithStatistics("+weight+")"
  println("EntropyBagOfWordsPriorWithStatistics("+weight+")")
  def unroll1(exists:EntityExists) = Factor(exists,exists.entity.attr[IsEntity],exists.entity.attr[B])
  def unroll2(isEntity:IsEntity) = Factor(isEntity.entity.attr[EntityExists],isEntity,isEntity.entity.attr[B])
  def unroll3(bag:B) = Factor(bag.entity.attr[EntityExists],bag.entity.attr[IsEntity],bag)//throw new Exception("An entitie's status as a mention should never change.")
  def score(exists:EntityExists#Value, isEntity:IsEntity#Value, bag:B#Value): Double ={
    var entropy = 0.0
    var n = 0.0
    if(exists.booleanValue /*&& isEntity.booleanValue*/){
      val l1Norm = bag.l1Norm
      for((k,v) <- bag.iterator){
        entropy -= (v/l1Norm)*math.log(v/l1Norm)
        n+=1.0
      }
    }
    if(n>1)entropy /= scala.math.log(n) //normalized entropy in [0,1]
    entropy = -entropy*weight
    if(_debug)println("  "+debug(entropy))
    entropy
  }
  //def sigmoid(v:Double):Double = 1/(1+scala.math.exp(-v))
}
class BagOfWordsPriorWithStatistics[B<:BagOfWordsVariable with EntityAttr](val weight:Double=1.0)(implicit m:Manifest[B]) extends TupleTemplateWithStatistics3[EntityExists,IsEntity,B]{
  println("BagOfWordsPriorWithStatistics("+weight+")")
  def unroll1(exists:EntityExists) = Factor(exists,exists.entity.attr[IsEntity],exists.entity.attr[B])
  def unroll2(isEntity:IsEntity) = Factor(isEntity.entity.attr[EntityExists],isEntity,isEntity.entity.attr[B])
  def unroll3(bag:B) = Factor(bag.entity.attr[EntityExists],bag.entity.attr[IsEntity],bag)//throw new Exception("An entitie's status as a mention should never change.")
  def score(exists:EntityExists#Value, isEntity:IsEntity#Value, bag:B#Value): Double ={
    var result = 0.0
    if(exists.booleanValue /*&& isEntity.booleanValue*/ && bag.size>0)result =  bag.size.toDouble/bag.l1Norm
    -result*weight
  }
}
class EntitySizePrior(val weight:Double=0.1, val exponent:Double=1.2, val saturation:Double=128) extends TupleTemplateWithStatistics3[EntityExists,IsEntity,MentionCountVariable]{
  println("EntitySizePrior: "+weight+" exponent: "+exponent)
  def unroll1(exists:EntityExists) = Factor(exists,exists.entity.attr[IsEntity],exists.entity.attr[MentionCountVariable])
  def unroll2(isEntity:IsEntity) = Factor(isEntity.entity.attr[EntityExists],isEntity,isEntity.entity.attr[MentionCountVariable])
  def unroll3(mentionCount:MentionCountVariable) = Factor(mentionCount.entity.attr[EntityExists],mentionCount.entity.attr[IsEntity],mentionCount)//throw new Exception("An entitie's status as a mention should never change.")
  def score(exists:EntityExists#Value, isEntity:IsEntity#Value, mentionCount:MentionCountVariable#Value): Double =
    if(exists.booleanValue && isEntity.booleanValue)scala.math.min(saturation,scala.math.pow(mentionCount.intValue,1.2)) * weight else 0.0
}
class BagOfWordsCubbie extends Cubbie{
  def store(bag:BagOfWords) = {_map ++= bag.asHashMap;this}
  def fetch:HashMap[String,Double] = {
    val result = new HashMap[String,Double]
    for((k,v) <- _map)result += k -> v.toString.toDouble
    result
  }
}

/**Basic trait for doing operations with bags of words*/
trait BagOfWords{ // extends scala.collection.Map[String,Double]{
  //def empty: This
  def size:Int
  def asHashMap:HashMap[String,Double]
  def apply(word:String):Double
  def iterator:Iterator[(String,Double)]
  def l2Norm:Double
  def l1Norm:Double
  def *(that:BagOfWords):Double
  def deductedDot(that:BagOfWords, deduct:BagOfWords):Double
  def cosineSimilarityINEFFICIENT(that:BagOfWords,deduct:BagOfWords):Double ={
    //println("  (1) bag: "+that)
    //println("  (1) that   : "+that.l2Norm)
    //println("  (1) that bf: "+that.l2NormBruteForce)
    that.removeBag(deduct)
    //println("  (2) bag: "+that)
    //println("  (2) that   : "+that.l2Norm)
    //println("  (2) that bf: "+that.l2NormBruteForce)
    val result = cosineSimilarity(that)
    that.addBag(deduct)
    //println("  (3) bag: "+that)
    //println("  (3) that   : "+that.l2Norm)
    //println("  (3) that bf: "+that.l2NormBruteForce)
    result
  }
  def cosineSimilarity(that:BagOfWords,deduct:BagOfWords):Double ={
    //val smaller = if(this.size<that.size)this else that
    //val larger = if(that.size<this.size)this else that
    val numerator:Double = this.deductedDot(that,deduct)
    if(numerator!=0.0){
      val thatL2Norm = Math.sqrt(deduct.l2Norm*deduct.l2Norm+that.l2Norm*that.l2Norm - 2*(deduct * that))
      val denominator:Double = this.l2Norm*thatL2Norm
      if(denominator==0.0 || denominator != denominator) 0.0 else numerator/denominator
    } else 0.0
  }
  def cosineSimilarity(that:BagOfWords):Double = {
    val numerator:Double = this * that
    val denominator:Double = this.l2Norm*that.l2Norm
    if(denominator==0.0 || denominator != denominator) 0.0 else numerator/denominator
  }
  def +=(s:String,w:Double=1.0):Unit
  def -=(s:String,w:Double=1.0):Unit
  def ++=(that:BagOfWords):Unit = for((s,w) <- that.iterator)this += (s,w)
  def --=(that:BagOfWords):Unit = for((s,w) <- that.iterator)this -= (s,w)
  def contains(s:String):Boolean
  def l2NormBruteForce:Double = {
    var result=0.0
    for((k,v) <- iterator)
      result += v*v
    scala.math.sqrt(result)
  }
  def addBag(that:BagOfWords):Unit
  def removeBag(that:BagOfWords):Unit
}

class SparseBagOfWords(initialWords:Iterable[String]=null,initialBag:Map[String,Double]=null) extends BagOfWords{
  var variable:BagOfWordsVariable = null
  protected var _l2Norm = 0.0
  protected var _l1Norm = 0.0
  protected var _bag = new LinkedHashMap[String,Double]//TODO: try LinkedHashMap
  /*
  def buildFrom(_bag:HashMap[String,Double], _l1Norm:Double, _l2Norm:Double) ={
    this._bag = _bag
    this._l1Norm= _l1Norm
    this._l2Norm= _l2Norm
  }*/
  def clear:Unit ={
    _l2Norm=0.0
    _l1Norm=0.0
    _bag = new LinkedHashMap[String,Double]
  }
  //def underlying = _bag
  def sizeHint(n:Int) = _bag.sizeHint(n)
  if(initialWords!=null)for(w<-initialWords)this += (w,1.0)
  if(initialBag!=null)for((k,v)<-initialBag)this += (k,v)
  def l2Norm = scala.math.sqrt(_l2Norm)
  def l1Norm = _l1Norm
  def asHashMap:HashMap[String,Double] = {val result = new HashMap[String,Double];result ++= _bag;result}
  override def toString = _bag.toString
  def apply(s:String):Double = _bag.getOrElse(s,0.0)
  def contains(s:String):Boolean = _bag.contains(s)
  def size = _bag.size
  def iterator = _bag.iterator
  def *(that:BagOfWords) : Double = {
    if(that.size<this.size)return that * this
    var result = 0.0
    for((k,v) <- iterator)result += v*that(k)
    result
  }
  def deductedDot(that:BagOfWords,deduct:BagOfWords) : Double = {
    var result = 0.0
    if(deduct eq this)for((k,v) <- iterator)result += v*(that(k) - v)
    else for((k,v) <- iterator)result += v*(that(k) - deduct(k))
    result
  }
  /*
  override def ++=(that:BagOfWords):Unit = for((s,w) <- that.iterator){
    _bag.sizeHint(this.size+that.size)
    this += (s,w)
  }*/
  //def --=(that:BagOfWords):Unit = for((s,w) <- that.iterator)this -= (s,w)
  def += (s:String, w:Double=1.0):Unit ={
    if(w!=0.0){
      //if(w!=1.0)println("  add: "+w)
      _l1Norm += w
      _l2Norm += w*w + 2*this(s)*w
      _bag(s) = _bag.getOrElse(s,0.0) + w
    }
  }
  def -= (s:String, w:Double=1.0):Unit ={
    if(w!=0.0){
      _l1Norm -= w
      _l2Norm += w*w - 2.0*this(s)*w
      //if(w!=1.0)println("  remove: "+w)
      if(withinEpsilon(w, _bag(s)))_bag.remove(s)
      else _bag(s) = _bag.getOrElse(s,0.0) - w
    }
  }
  @inline final def withinEpsilon(v1:Double, v2:Double, epsilon:Double=0.000001):Boolean = if(v1==v2)true else ((v1-v2).abs<=epsilon)
  def addBag(that:BagOfWords) ={
    //that match{case t:SparseBagOfWords=>t.sizeHint(this.size+that.size)}
    for((k,v) <- that.iterator) this += (k,v)
  }
  def removeBag(that:BagOfWords) = for((k,v) <- that.iterator)this -= (k,v)
}


trait BagOfWordsVar extends Var with VarAndValueGenericDomain[BagOfWordsVar,SparseBagOfWords] with Iterable[(String,Double)]
class BagOfWordsVariable(initialWords:Iterable[String]=Nil,initialMap:Map[String,Double]=null) extends BagOfWordsVar with VarAndValueGenericDomain[BagOfWordsVariable,SparseBagOfWords] {
  // Note that the returned value is not immutable.
  def value = _members
  def clear = _members.clear
  private val _members:SparseBagOfWords = {
    val result = new SparseBagOfWords(initialWords)
    if(initialMap!=null)for((k,v) <- initialMap)result += (k,v)
    result
  }
  _members.variable = this
  def members: SparseBagOfWords = _members
  def iterator = _members.iterator
  override def size = _members.size
  def contains(x:String) = _members.contains(x)
  def accept:Unit ={} //_members.incorporateBags
  def add(x:String,w:Double=1.0)(implicit d:DiffList):Unit = {
    if(d!=null) d += new BagOfWordsVariableAddStringDiff(x,w)
    _members += (x,w)
  }
  def remove(x:String,w:Double = 1.0)(implicit d:DiffList):Unit = {
    if(d!=null) d += new BagOfWordsVariableRemoveStringDiff(x,w)
    _members -= (x,w)
  }
  def add(x:BagOfWords)(implicit d: DiffList): Unit =  {
    if (d != null) d += new BagOfWordsVariableAddBagDiff(x)
    _members.addBag(x)
  }
  def remove(x: BagOfWords)(implicit d: DiffList): Unit = {
    if (d != null) d += new BagOfWordsVariableRemoveBagDiff(x)
    _members.removeBag(x)
  }
  final def += (x:String,w:Double=1.0):Unit = add(x,w)(null)
  final def -= (x:String,w:Double=1.0):Unit = remove(x,w)(null)
  final def +=(x:BagOfWords): Unit = add(x)(null)
  final def -=(x:BagOfWords): Unit = remove(x)(null)
  final def ++=(xs:Iterable[String]): Unit = xs.foreach(add(_)(null))
  final def --=(xs:Iterable[String]): Unit = xs.foreach(remove(_)(null))
  final def ++=(xs:HashMap[String,Double]): Unit = for((k,v)<-xs)add(k,v)(null)
  final def --=(xs:HashMap[String,Double]): Unit = for((k,v)<-xs)remove(k,v)(null)
  case class BagOfWordsVariableAddStringDiff(added: String,w:Double) extends Diff {
    // Console.println ("new SetVariableAddDiff added="+added)
    def variable: BagOfWordsVariable = BagOfWordsVariable.this
    def redo = _members += (added,w)
    def undo = _members -= (added,w)
    override def toString = "BagOfWordsVariableAddStringDiff of " + added + " to " + BagOfWordsVariable.this
  }
  case class BagOfWordsVariableRemoveStringDiff(removed: String,w:Double) extends Diff {
    //        Console.println ("new SetVariableRemoveDiff removed="+removed)
    def variable: BagOfWordsVariable = BagOfWordsVariable.this
    def redo = _members -= (removed,w)
    def undo = _members += (removed,w)
    override def toString = "BagOfWordsVariableRemoveStringDiff of " + removed + " from " + BagOfWordsVariable.this
  }
  case class BagOfWordsVariableAddBagDiff(added:BagOfWords) extends Diff {
    // Console.println ("new SetVariableAddDiff added="+added)
    def variable: BagOfWordsVariable = BagOfWordsVariable.this
    def redo = _members.addBag(added)
    def undo = _members.removeBag(added)
    override def toString = "BagOfWordsVariableAddBagDiff of " + added + " to " + BagOfWordsVariable.this
  }
  case class BagOfWordsVariableRemoveBagDiff(removed: BagOfWords) extends Diff {
    //        Console.println ("new SetVariableRemoveDiff removed="+removed)
    def variable: BagOfWordsVariable = BagOfWordsVariable.this
    def redo = _members.removeBag(removed)
    def undo = _members.addBag(removed)
    override def toString = "BagOfWordsVariableRemoveBagDiff of " + removed + " from " + BagOfWordsVariable.this
  }
}
/*
object BagOfWordsTests{
  def main(args:Array[String]) ={
    
  }

  def nextTestPair:(BagOfWordsVariable,BagOfWordsTensorVariable) ={
    val bow = new BagOfWordsVariable
    val bowTensor = new BagOfWordsTensorVariable
    (bow,bowTensor)
  }
}
*/

object DefaultBagOfWordsDomain extends CategoricalDimensionTensorDomain[String]
/*
object TokenFeaturesDomain extends CategoricalTensorDomain[String]
  class TokenFeatures(val token:Token) extends BinaryFeatureVectorVariable[String] {
    def domain = TokenFeaturesDomain
  }
 def domain = AffinityVectorDomain
 */
class BagOfWordsTensorVariable(val domain:CategoricalDimensionTensorDomain[String]=DefaultBagOfWordsDomain) extends FeatureVectorVariable[String]{
  //final def ++=(xs:HashMap[String,Double]): Unit = for((k,v)<-xs)increment(k,v)(null)
}
object BagOfWordsUtil{
  def cosineDistance(v1:SparseIndexedTensor,v2:SparseIndexedTensor,deduct:Boolean=false):Double ={
    val v = if(deduct)v2-v1 else v2
    val numerator = v dot v1
    val denominator = v.twoNorm * v.twoNorm
    if(denominator==0.0 || denominator != denominator) 0.0 else numerator/denominator
  }
}
class TensorBagOfWordsCubbie extends Cubbie{
  def store(bag:BagOfWordsTensorVariable) = {
    bag.value.foreachActiveElement((k,v) => {
      _map += bag.domain._dimensionDomain.category(k) -> v
    })
    this
  }
  def fetch:HashMap[String,Double] = {
    val result = new HashMap[String,Double]
    for((k,v) <- _map)result += k -> v.toString.toDouble
    result
  }
}

/**Handles loading/storing issues generic to entity collections with canopies, ids, priorities, and hierarchical structure. Agnostic to DB technology (sql, mongo, etc)*/
trait DBEntityCubbie[T<:HierEntity with HasCanopyAttributes[T] with Prioritizable] extends Cubbie {
  val canopies = new StringListSlot("canopies")
  val inferencePriority = new DoubleSlot("ipriority")
  val parentRef = RefSlot("parentRef", () => newEntityCubbie)
  val isMention = BooleanSlot("isMention")
  val groundTruth = new StringSlot("gt")
  val bagOfTruths = new CubbieSlot("gtbag", () => new BagOfWordsCubbie)
  val mentionCount = new IntSlot("mc")
  def newEntityCubbie:DBEntityCubbie[T]
  def fetch(e:T) ={
    if(inferencePriority.isDefined)e.priority = inferencePriority.value
    if(isMention.isDefined){e.attr[IsMention].set(isMention.value)(null);e.isObserved=isMention.value}
    e.attr[IsEntity].set(e.isRoot)(null)
    e.attr[EntityExists].set(e.isConnected)(null)
    if(mentionCount.isDefined)e.attr[MentionCountVariable].set(mentionCount.value)(null)
    if(groundTruth.isDefined)e.groundTruth = Some(groundTruth.value)
    if(bagOfTruths.isDefined && e.attr[BagOfTruths]!=null)e.attr[BagOfTruths] ++= bagOfTruths.value.fetch
    //note that entity parents are set externally not inside the cubbie
  }
  def store(e:T) ={
    //println("Storing priority: "+e.priority + " for "+e.canopyAttributes.head.canopyName)
    this.id=e.id
    canopies := e.canopyAttributes.map(_.canopyName).toSeq
    inferencePriority := e.priority
    isMention := e.attr[IsMention].booleanValue
    if(e.parentEntity!=null)parentRef := e.parentEntity.id
    if(e.groundTruth != None)groundTruth := e.groundTruth.get
    mentionCount := e.attr[MentionCountVariable].value
  }
}
trait EntityCollection[E<:HierEntity]{
  def += (e:E):Unit
  def ++= (es:Iterable[E]):Unit
  def drop:Unit
  def store(entitiesToStore:Iterable[E]):Unit
  def nextBatch(n:Int=10):Seq[E]
  def loadAll:Seq[E]
  def loadByCanopies(canopies:Seq[String]):Seq[E]
  def loadByIds(ids:Seq[Any]):Seq[E]
  def loadLabeledAndCanopies:Seq[E]
  def loadLabeled:Seq[E]
  var printProgress=true
}
trait DBEntityCollection[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable, C<:DBEntityCubbie[E]] extends EntityCollection[E]{
  protected var _id2cubbie:HashMap[Any,C] = null
  protected var _id2entity:HashMap[Any,E] = null
  protected def newEntityCubbie:C
  protected def newEntity:E
  protected def fetchFromCubbie(c:C,e:E):Unit
  protected def register(entityCubbie:C) = _id2cubbie += entityCubbie.id->entityCubbie
  protected def wasLoadedFromDB(entity:E) = _id2cubbie.contains(entity.id)
  protected def entity2cubbie(e:E):C =  _id2cubbie(e.id)
  protected def putEntityInCubbie(e:E) = {val ec=newEntityCubbie;ec.store(e);ec}
  protected def entityCubbieColl:MutableCubbieCollection[C]
  protected def changePriority(e:E):Unit ={
    val oldPriority = e.priority
    //e.priority = e.priority-1.0


    //println("  prio change for "+e.canopyAttributes.head.canopyName+": "+oldPriority +"-->"+e.priority)
    /*
    //e.priority = scala.math.exp(e.priority - random.nextDouble)
    if(random.nextDouble>0.25)e.priority = scala.math.min(e.priority,random.nextDouble)
    else e.priority = random.nextDouble
    */
  }
  def += (e:E):Unit = insert(e)
  def ++= (es:Iterable[E]):Unit = insert(es)
  def insert (c:C) = entityCubbieColl += c
  def insert (e:E) = entityCubbieColl += putEntityInCubbie(e)
  //def insert(cs:Iterable[C]) = entityCubbieColl ++= cs
  def insert(es:Iterable[E]) = entityCubbieColl ++= es.map(putEntityInCubbie(_))
  def drop:Unit
  def finishLoadAndReturnEntities(entityCubbies:Iterable[C]) = {
    val entities = new ArrayBuffer[E]
    for(cubbie <- entityCubbies){
      val entity:E = newEntity
      fetchFromCubbie(cubbie,entity)
      register(cubbie)
      entities += entity
    }
    entities
  }
  def reset:Unit ={
    _id2cubbie = new HashMap[Any,C]
    _id2entity = new HashMap[Any,E]
  }
  def store(entitiesToStore:Iterable[E]):Unit ={
    val deleted = new ArrayBuffer[E]
    val updated = new ArrayBuffer[E]
    val created = new ArrayBuffer[E]
    var timer = System.currentTimeMillis
    if(printProgress)print("Updating priorities...")
    for(e <- entitiesToStore)changePriority(e)
    if(printProgress)println("Done")
    if(printProgress)print("Identifying changed identities...")
    for(e<-entitiesToStore){
      if(e.isConnected){
        if(wasLoadedFromDB(e))updated += e
        else created += e
      }
      if(!e.isConnected && wasLoadedFromDB(e))deleted += e
      //else if(!e.isConnected && wasLoadedFromDB(e))entityCubbieColl += {val ec=newEntityCubbie;ec.store(e);ec}
    }
    //for(e <- updated.iterator ++ created.iterator)if(!(e.isEntity.booleanValue && e.isMention.booleanValue))e.rootIdOpt = Some(e.entityRoot.id.toString)
    if(printProgress)println("Done")
    if(printProgress)print("About to delete "+deleted.size+" entities...")
    removeEntities(deleted)
    if(printProgress)println("Done")
    if(printProgress)print("About to update "+updated.size+" entities...")
    val updateTimer = System.currentTimeMillis
    var updateTimerElapsed = System.currentTimeMillis
    var count = 0
    for(e <- updated){
      entityCubbieColl.updateDelta(_id2cubbie.getOrElse(e.id,null).asInstanceOf[C],putEntityInCubbie(e))
      count += 1
      val windowElapsed = (System.currentTimeMillis- updateTimerElapsed)/1000L
      if(printProgress && windowElapsed > 30){
        val elapsed = (System.currentTimeMillis() - updateTimer)/1000L
        updateTimerElapsed = System.currentTimeMillis
        println("processed "+count + " in "+elapsed + " seconds.")
      }
    }
    if(printProgress)println("Done")
    if(printProgress)print("About to insert "+created.size+" entities...")
    for(e <- created)entityCubbieColl += putEntityInCubbie(e)
    if(printProgress)println("Done")
    if(printProgress)println("Saving took "+((System.currentTimeMillis() - timer)/1000L)+" seconds.")
    deletedHook(deleted)
    updatedHook(updated)
    createdHook(created)
    if(printProgress)println("deleted: "+deleted.size)
    if(printProgress)println("updated: "+updated.size)
    if(printProgress)println("created: "+created.size)
  }
  protected def deletedHook(deleted:Seq[E]):Unit ={}
  protected def updatedHook(updated:Seq[E]):Unit ={}
  protected def createdHook(created:Seq[E]):Unit ={}
  /**This is database specific for example because mongo has a specialized _id field*/
  protected def removeEntities(entitiesToRemove:Seq[E]):Unit
  def assembleEntities(toAssemble:TraversableOnce[C],id2entity:Any=>E):Unit ={
    if(printProgress)println("Assembling entities")
    var count = 0
    var startTime = System.currentTimeMillis
    var intervalTime = System.currentTimeMillis
    for(c<-toAssemble){
      count += 1
      if(printProgress){
        if(count % 10000 == 0)print(".")
        if(count % (25*10000) == 0){
          val elapsed = (System.currentTimeMillis - startTime)/1000L
          val elapsedInterval = (System.currentTimeMillis - intervalTime)/1000L
          println(count+" total time: "+(elapsed)+" elasped: "+(elapsedInterval))
          intervalTime = System.currentTimeMillis
        }
      }
      val child = id2entity(c.id)
      val parent = if(c.parentRef.isDefined)id2entity(c.parentRef.value) else null.asInstanceOf[E]
      child.setParentEntity(parent)(null)
    }
  }
  def loadByIds(ids:Seq[Any]):Seq[E] ={
    reset
    if(printProgress)println("Loading a list of "+ids.size+ " ids.")
    val cubbies = entityCubbieColl.findByIds(ids) //entityColl.find(ids)
    val entities = (for(entityCubbie <- cubbies)yield{
      val e = newEntity
      entityCubbie.fetch(e)
      e
    }).toSeq
    for(entityCubbie <- cubbies)_id2cubbie += entityCubbie.id -> entityCubbie
    assembleEntities(cubbies, (id:Any)=>{_id2entity.getOrElse(id,{println("Warning: id "+id+" not found while assembling entities. Assuming no parent.");null.asInstanceOf[E]})})
    entities
  }
}
abstract class MongoDBEntityCollection[E<:HierEntity with HasCanopyAttributes[E] with Prioritizable,C<:DBEntityCubbie[E]](val name:String, mongoDB:DB) extends DBEntityCollection[E,C]{
  import MongoCubbieImplicits._
  import MongoCubbieConverter._
  protected val entityColl = mongoDB.getCollection(name)
  val entityCubbieColl = new MongoCubbieCollection(entityColl,() => newEntityCubbie,(a:C) => Seq(Seq(a.canopies),Seq(a.inferencePriority),Seq(a.parentRef),Seq(a.bagOfTruths))) with LazyCubbieConverter[C]
  protected def removeEntities(deleted:Seq[E]):Unit = for(e <- deleted)entityCubbieColl.remove(_.idIs(entity2cubbie(e).id))
  //TODO: generalize MongoSlot so that we can move this into the EnttiyCollection class
  def drop:Unit = entityColl.drop
  def loadLabeled:Seq[E] ={
    reset
    val result = new ArrayBuffer[C]
    result ++= entityCubbieColl.query(_.bagOfTruths.exists(true))
    val initialEntities = (for(entityCubbie<-result) yield {val e = newEntity;entityCubbie.fetch(e);e}).toSeq
    for(entityCubbie <- result)_id2cubbie += entityCubbie.id -> entityCubbie
    for(entity <- initialEntities)_id2entity += entity.id -> entity
    println("initial entities: "+initialEntities.size)
    println("initial cubbies : "+result.size)
    println("_id2cubbie: "+ _id2cubbie.size)
    println("_id2entity: "+ _id2entity.size)
    assembleEntities(result, (id:Any)=>{_id2entity.getOrElse(id,{println("Warning: id "+id+" not found while assembling entities. Assuming no parent.");null.asInstanceOf[E]})})
    initialEntities
  }
  def loadLabeledAndCanopies:Seq[E] ={
    reset
    val result = new ArrayBuffer[C]
    val labeled = new ArrayBuffer[C]
    val canopyHash = new HashSet[String]
    labeled ++= entityCubbieColl.query(_.bagOfTruths.exists(true))
    //add canopies
    for(entity <- labeled){
      for(name <- entity.canopies.value){
        if(!canopyHash.contains(name)){
          result ++= entityCubbieColl.query(_.canopies(Seq(name)))
          canopyHash += name
        }
      }
    }
    val initialEntities = (for(entityCubbie<-result) yield {val e = newEntity;entityCubbie.fetch(e);e}).toSeq
    for(entityCubbie <- result)_id2cubbie += entityCubbie.id -> entityCubbie
    for(entity <- initialEntities)_id2entity += entity.id -> entity
    println("initial entities: "+initialEntities.size)
    println("initial cubbies : "+result.size)
    println("_id2cubbie: "+ _id2cubbie.size)
    println("_id2entity: "+ _id2entity.size)
    assembleEntities(result, (id:Any)=>{_id2entity(id)})
    initialEntities
  }
  def loadProcessed(numToTake:Int=Integer.MAX_VALUE):Seq[E] ={
    reset
    println("Loading processed")
    val canopyHash = new HashSet[String]
    var result = new ArrayBuffer[C]
    val qresult = entityCubbieColl.query(_.parentRef.exists(false).isMention(false),_.canopies.select)
    var count = 0
    val qtime = System.currentTimeMillis
    println("About to load entities for canopies.")
    while(count<numToTake && qresult.hasNext){
      val ec = qresult.next
      for(name <- ec.canopies.value){
        if(!canopyHash.contains(name)){
          val elapsedTime = System.currentTimeMillis
          result ++= entityCubbieColl.query(_.canopies(Seq(name)))
          canopyHash += name
          count += 1
          //println("  query took "+((System.currentTimeMillis-elapsedTime)/1000L) + " sec.")
        }
      }
    }
    println("Loading entities for canopies took "+ ((System.currentTimeMillis - qtime)/1000L) + " seconds.")
    println("Found "+count + " top level entities.")
    print("Instantiating factorie variables...")
    val initialEntities = (for(entityCubbie<-result) yield {val e = newEntity;entityCubbie.fetch(e);e}).toSeq
    println("Done.")
    print("Creating cubbie index...")
    for(entityCubbie <- result)_id2cubbie += entityCubbie.id -> entityCubbie
    println("Done.")
    print("Creating entity index...")
    for(entity <- initialEntities)_id2entity += entity.id -> entity
    println("Done.")
    println("Found "+result.size+" entities in "+canopyHash.size + " canopies.")
    println("  initial entities: "+initialEntities.size)
    println("  initial cubbies : "+result.size)
    println("  _id2cubbie: "+ _id2cubbie.size)
    println("  _id2entity: "+ _id2entity.size)
    assembleEntities(result, (id:Any)=>{_id2entity(id)})
    initialEntities
  }

  def loadByCanopies(canopies:Seq[String]):Seq[E] ={
    reset
    val result = new ArrayBuffer[C]
    var qtime = System.currentTimeMillis
//    println("About to load entities from "+canopies.size+" canopies.")
    for(name <- canopies){
      result ++= entityCubbieColl.query(_.canopies(Seq(name)))
    }
//    println("Loading "+result.size+" cubbies from "+canopies.size + " canopies took "+(System.currentTimeMillis-qtime)/1000L+" seconds.")
    qtime = System.currentTimeMillis
    val initialEntities:Seq[E] = (for(entityCubbie<-result.par) yield {val e = newEntity;entityCubbie.fetch(e);e}).seq
//    println("Converting cubbies to entities took  "+(System.currentTimeMillis-qtime)/1000L+" seconds.")
    for(entityCubbie <- result)_id2cubbie += entityCubbie.id -> entityCubbie
    for(entity <- initialEntities)_id2entity += entity.id -> entity
    assembleEntities(result, (id:Any)=>{_id2entity.getOrElse(id,{println("Warning: id "+id+" not found while assembling entities. Assuming no parent.");null.asInstanceOf[E]})})
    initialEntities
  }
  def nextBatch(n:Int=10):Seq[E] ={
    reset
    val canopyHash = new HashSet[String]
    var result = new ArrayBuffer[C]
    var topPriority = new ArrayBuffer[C]
    val sorted = entityCubbieColl.query(null,_.canopies.select.inferencePriority.select).sort(_.inferencePriority(-1))
    println("Printing top canopies")
    for(i <- 0 until n)if(sorted.hasNext){
      topPriority += sorted.next
      if(i<10)println("  "+topPriority(i).inferencePriority.value+": "+topPriority(i).canopies.value.toSeq)
    }
    sorted.close
    var count = 0
    val qtime = System.currentTimeMillis
    println("About to load entities for canopies.")
    for(entity <- topPriority){
      for(name <- entity.canopies.value){
        if(!canopyHash.contains(name)){
          val elapsedTime = System.currentTimeMillis
          result ++= entityCubbieColl.query(_.canopies(Seq(name)))
          canopyHash += name
          count += 1
          //println("  query took "+((System.currentTimeMillis-elapsedTime)/1000L) + " sec.")
        }
      }
    }
    println("Loading entities for canopies took "+ ((System.currentTimeMillis - qtime)/1000L) + " seconds.")
    print("Instantiating factorie variables...")
    val initialEntities = (for(entityCubbie<-result) yield {val e = newEntity;entityCubbie.fetch(e);e}).toSeq
    println("Done.")
    print("Creating cubbie index...")
    for(entityCubbie <- result)_id2cubbie += entityCubbie.id -> entityCubbie
    println("Done.")
    print("Creating entity index...")
    for(entity <- initialEntities)_id2entity += entity.id -> entity
    println("Done.")
    println("Loaded "+n+" entities to determine canopies. Found "+result.size+" entities in "+canopyHash.size + " canopies.")
    println("  initial entities: "+initialEntities.size)
    println("  initial cubbies : "+result.size)
    println("  _id2cubbie: "+ _id2cubbie.size)
    println("  _id2entity: "+ _id2entity.size)
    assembleEntities(result, (id:Any)=>{_id2entity(id)})
    initialEntities
  }
  def loadAll:Seq[E] ={
    reset
    println("Loading entire collection")
    var count = 0
    print("  getting iterator...")
    val cubbies = allEntityCubbies.toSeq
    println("  done")
    var startTime = System.currentTimeMillis
    var intervalTime = System.currentTimeMillis
    val initialEntities = (for(entityCubbie<-cubbies) yield {
      count += 1
      if(count < 10000){
        if(count % 10 == 0)print(".")
        if(count % 500 ==0)println(count + " " + (System.currentTimeMillis - startTime)/1000L)
      }
      if(count % 10000 == 0)print(".")
      if(count % (25*10000) == 0){
        val elapsed = (System.currentTimeMillis - startTime)/1000L
        val elapsedInterval = (System.currentTimeMillis - intervalTime)/1000L
        println(count+" total time: "+(elapsed)+" elasped: "+(elapsedInterval))
        intervalTime = System.currentTimeMillis
      }
      val e = newEntity
      entityCubbie.fetch(e)
      e}).toSeq
    for(entityCubbie <- cubbies)_id2cubbie += entityCubbie.id -> entityCubbie
    for(entity <- initialEntities)_id2entity += entity.id -> entity
    println("  initial entities: "+initialEntities.size)
    println("  initial cubbies : "+cubbies.size)
    println("  _id2cubbie: "+ _id2cubbie.size)
    println("  _id2entity: "+ _id2entity.size)
    assembleEntities(cubbies, (id:Any)=>{_id2entity(id)})
    initialEntities
  }
  def allEntityCubbies:Seq[C] =entityCubbieColl.iterator.toSeq
  def freshCopy(cubbies:Seq[C]):Seq[E] =(for(entityCubbie<-cubbies) yield {val e = newEntity;entityCubbie.fetch(e);e}).toSeq
}
