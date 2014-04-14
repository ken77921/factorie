package cc.factorie.app.nlp.coref

import cc.factorie.app.nlp.wordnet.WordNet
import cc.factorie.app.nlp.{Token, Document, DocumentAnnotator}
import cc.factorie.util.coref.{CorefEvaluator, GenericEntityMap}
import java.util.concurrent.ExecutorService
import cc.factorie.optimize._
import java.io._
import cc.factorie.util.{ClasspathURL, BinarySerializer}
import scala.collection.mutable.ArrayBuffer
import cc.factorie.app.nlp.coref.mention._
import cc.factorie.app.nlp.phrase.{PhraseNumber, PhraseGender, OntonotesPhraseEntityType}

/**
 * User: apassos
 * Date: 6/27/13
 * Time: 12:25 PM
 */

abstract class ForwardCorefBase extends CorefSystem {
  val options = new Coref1Options

  def tokenAnnotationString(token:Token): String = {
    val emap = token.document.attr[GenericEntityMap[Mention]]
    token.document.attr[MentionList].filter(mention => mention.phrase.contains(token)) match {
      //case ms:Seq[Mention] if ms.length > 0 => ms.map(m => m.phrase.attr[Mention].categoryValue+":"+m.phrase.indexOf(token)+"e"+emap.getEntity(m)).mkString(", ")
      case _ => "_"
    }
  }

  def generateTrainingInstances(d: Document, map: GenericEntityMap[Mention]): Seq[MentionPairLabel] = {
    generateFeatures(d.attr[MentionList].map(CorefMention.mentionToCorefMention), map)
  }

  protected def generateFeatures(mentions: Seq[CorefMention], map: GenericEntityMap[Mention] = null): Seq[MentionPairLabel] = {
    val labels = new ArrayBuffer[MentionPairLabel]
    for (i <- 0 until mentions.size){
      if(!options.usePronounRules || !mentions(i).isPRO)
        labels ++= generateMentionPairLabelsForOneAnaphor(mentions, i, map)
    }
    /*if (Options.debug) println("# of ex = " + labels.size)*/
    labels
  }

  protected def generateMentionPairLabelsForOneAnaphor(orderedMentions: Seq[CorefMention], anaphorIndex: Int, map: GenericEntityMap[Mention] = null): Seq[MentionPairLabel] = {
    val labels = new ArrayBuffer[MentionPairLabel]
    val m1 = orderedMentions(anaphorIndex)
    var numAntecedent = 0
    var i = anaphorIndex - 1
    while (i >= 0 && (numAntecedent < options.numPositivePairsTrain || !options.pruneNegTrain)) {
      val m2 = orderedMentions(i)
      val label = m1.parentEntity == m2.parentEntity
      if (!pruneMentionPairTraining(m1,m2,label,numAntecedent)) {
        val cl = new MentionPairLabel(model, m1, m2, orderedMentions, label, options=options)
        if(label) numAntecedent += 1
        // merge features from neighboring mentions that are in the same chain as m2
        assert(map != null, "Options.mergeNeighborFeatures requires non-null mention-entityID map")
        val mergeables = labels.filter(l => map.reverseMap(l.mention2.mention) == map.reverseMap(m2.mention))
        mergeFeatures(cl.features, mergeables.map(_.features))
        labels += cl
      }
      i -= 1
    }
    labels
  }

  def pruneMentionPairTraining(anaphor: CorefMention,antecedent: CorefMention,label:Boolean,numAntecedents:Int): Boolean = {
    var skip = false
    val cataphora = antecedent.isPRO && !anaphor.isPRO
    if(options.usePronounRules && antecedent.isPRO) skip = true
    else if(cataphora) {
      if (label && !options.allowPosCataphora || !label && !options.allowNegCataphora) {
        skip = true
      }
    }
    if (label && numAntecedents > 0 && !options.pruneNegTrain) skip = true
    skip
  }

  def getTopTokenFrequencies(trainDocs:Seq[Document]) = {
    model.MentionPairLabelThing.tokFreq  ++= trainDocs.flatMap(_.tokens).groupBy(_.string.trim.toLowerCase.replaceAll("\\n", " ")).mapValues(_.size)
  }

  def mergeFeatures(l: MentionPairFeatures, mergeables: Seq[MentionPairFeatures]) {
    if (options.mergeFeaturesAtAll) {
      assert(l.features.activeCategories.forall(!_.startsWith("NBR")))
      val mergeLeft = ArrayBuffer[MentionPairLabel]()
      mergeables.take(1).diff(mergeLeft).foreach(l.features ++= _.features.mergeableAllFeatures.map("NBRR_" + _))
    }
  }

  case class CoarseMentionType(gender: String, number: String)

  def doCorefOnPronounsUsingRules(predMap: GenericEntityMap[CorefMention],allMentions: Seq[CorefMention]): Unit = {
    //first, make a map from entity ids in the predMap to sets of CoarseMentionTypes that appear somewhere in the entity.
    val numGenderSetsForEntities = predMap.entities.map(kv => (kv._1,kv._2.map(m => CoarseMentionType(m.gender,m.number)).toSet))
    //make such a set for every mention. For those mentions that aren't in entities, the set is just a singleton.
    val numGenderSets = allMentions.map(m => numGenderSetsForEntities.getOrElse(predMap.getEntity(m),Set(CoarseMentionType(m.gender,m.number))))
    for(i <- 0 until allMentions.length; if allMentions(i).isPRO){
      val m1 = allMentions(i)
      assert(numGenderSets(i).size == 1)
      val numGender1 = numGenderSets(i).head
      var j = i -1
      var searching = true
      while(j > 0 && searching){
        val m2 = allMentions(j)
        //find the first mention that isn't a pronoun and has a number-gender that is compatible with m1
        if(!m2.isPRO &&  numGenderSets(j).contains(numGender1)){
          predMap.addCoreferentPair(m1,m2)
          searching = false
        }
        j -= 1
      }
    }
  }

  def pruneMentionPairTesting(anaphor: CorefMention,antecedent: CorefMention): Boolean = {
    var skip = false
    val cataphora = antecedent.isPRO && !anaphor.isPRO
    if(options.usePronounRules && antecedent.isPRO) skip = true
    else if(cataphora || options.allowTestCataphora) skip = true
    skip
  }

  def processMention(orderedMentions: Seq[CorefMention], mentionIndex: Int, predMap: GenericEntityMap[Mention]): CorefMention = {
    val m1 = orderedMentions(mentionIndex)
    val candLabels = ArrayBuffer[MentionPairFeatures]()
    var bestCand: CorefMention = null
    var bestScore = Double.MinValue

    var j = mentionIndex - 1
    var numPositivePairs = 0
    while (j >= 0 && (numPositivePairs < options.numPositivePairsTest || !options.pruneNegTest)) {
      val m2 = orderedMentions(j)
      if (!pruneMentionPairTesting(m1,m2)) {
        val candLabel = new MentionPairFeatures(model, m1, m2, orderedMentions, options=options)
        val mergeables = candLabels.filter(l => predMap.reverseMap(l.mention2.mention) == predMap.reverseMap(m2.mention))
        mergeFeatures(candLabel, mergeables)
        candLabels += candLabel
        val score = if (m1.isProper && m1.nounWords.forall(m2.nounWords.contains) && m2.nounWords.forall(m1.nounWords.contains)  || options.mergeMentionWithApposition && (m1.isAppositionOf(m2) || m2.isAppositionOf(m1))) Double.PositiveInfinity
        else model.predict(candLabel.value)
        // Pronouns should always link to something
        if (score > 0.0) {
          numPositivePairs += 1
          if (bestScore <= score) {
            bestCand = m2
            bestScore = score
          }
        }
      }
      j -= 1
    }
    bestCand
  }
}


class ForwardCoref extends ForwardCorefBase {
  val model = new BaseCorefModel
}

class ForwardCorefImplicitConjunctions extends ForwardCorefBase {
  val model = new ImplicitCrossProductCorefModel
}

object ForwardCoref extends ForwardCoref {
  override def prereqAttrs: Seq[Class[_]] = Seq(classOf[OntonotesPhraseEntityType], classOf[PhraseGender], classOf[PhraseNumber])
  deserialize(new DataInputStream(ClasspathURL[ForwardCoref](".factorie").openConnection().getInputStream))
}

// This should only be used when using the NerAndPronounMentionFinder to find mentions
class NerForwardCoref extends ForwardCoref {
  override def prereqAttrs: Seq[Class[_]] = Seq(classOf[NerMentionList], classOf[OntonotesPhraseEntityType], classOf[PhraseGender], classOf[PhraseNumber])
}

object NerForwardCoref extends NerForwardCoref {
  deserialize(new DataInputStream(ClasspathURL[NerForwardCoref](".factorie").openConnection().getInputStream))
}


abstract class CorefSystem extends DocumentAnnotator{
  val model:PairwiseCorefModel
  val options:Coref1Options
  def prereqAttrs: Seq[Class[_]] = Seq(classOf[MentionList], classOf[OntonotesPhraseEntityType], classOf[PhraseGender], classOf[PhraseNumber])
  def postAttrs = Seq(classOf[GenericEntityMap[Mention]])
  def process(document: Document) = {
    val mentions = document.attr[MentionList].map(CorefMention.mentionToCorefMention)
    assertSorted(mentions)
    document.attr += processDocumentFromMentions(mentions)
    document
  }

  def processMention(mentions:Seq[CorefMention], i:Int, predMap:GenericEntityMap[Mention]): CorefMention
  def setTopTokenFrequencies(trainDocs:Seq[Document]):Unit
  def generateTrainingInstances[T](t:T,map:GenericEntityMap[Mention]):Seq[Example]

  abstract class LeftRightParallelTrainer[T](model: PairwiseCorefModel, optimizer: GradientOptimizer, trainTrueMaps: Map[String, GenericEntityMap[Mention]], val pool: ExecutorService, miniBatchSize: Int = 1){
    def pool:ExecutorService = pool
    def map(t: T,d:Document): Seq[Example] = MiniBatchExample(miniBatchSize,
      generateTrainingInstances(t, trainTrueMaps(d.name)).map(i => model.getExample(i, options.slackRescale)))
    def reduce(states: Iterable[Seq[Example]]) {
      for (examples <- states) {
        val trainer = new OnlineTrainer(model.parameters, optimizer, maxIterations = 1, logEveryN = examples.length - 1)
        trainer.trainFromExamples(examples)
      }
    }
    def runParallel(ins: Seq[Document]) {
      reduce(cc.factorie.util.Threading.parMap(ins, pool)(map))
    }
    def runSequential(ins: Seq[(T,Document)]) {
      reduce(ins.map(map))
    }
  }

  def processDocumentFromMentions(mentions: Seq[CorefMention]): GenericEntityMap[Mention] = {
    val predMap = new GenericEntityMap[Mention]
    assertSorted(mentions)
    mentions.foreach(m => predMap.addMention(m.mention, predMap.numMentions.toLong))
    for (i <- 0 until mentions.size) {
      val m1 = mentions(i)
      val bestCand = processMention(mentions, i, predMap)
      if (bestCand != null) {
        predMap.addCoreferentPair(m1.mention, bestCand.mention)
      }
    }
    predMap
  }

  def assertSorted(mentions: Seq[CorefMention]): Unit = {
    for(i <- 0 until mentions.length)
      assert(mentions(i).mention.phrase.tokens.head.stringStart >= mentions(i-1).mention.phrase.tokens.head.stringStart, "the mentions are not sorted by their position in the document. Error at position " +i+ " of " + mentions.length)
  }

  class CorefTester(model: PairwiseCorefModel, scorer: CorefScorer[Mention], scorerMutex: Object, testTrueMaps: Map[String, GenericEntityMap[Mention]], val pool: ExecutorService){
    def getPredMap(doc: Document, model: PairwiseCorefModel): GenericEntityMap[Mention] = process(doc).attr[GenericEntityMap[Mention]]
    def reduce(states: Iterable[Null]):Unit = { }
    def map(doc: Document): Unit = {
      val fId = doc.name
      val trueMap = testTrueMaps(fId)
      val predMap = getPredMap(doc, model)

      val gtMentions = trueMap.entities.values.flatten.toSet
      val predMentions = predMap.entities.values.flatten.toSet

      val nm = predMap.numMentions
      gtMentions.diff(predMentions).seq.toSeq.zipWithIndex.foreach(mi =>  predMap.addMention(mi._1, mi._2+ nm))
      val pw = CorefEvaluator.Pairwise.evaluate(predMap, trueMap)
      val b3 = CorefEvaluator.BCubedNoSingletons.evaluate(predMap, trueMap)
      val muc = CorefEvaluator.MUC.evaluate(predMap, trueMap)

      scorerMutex.synchronized {
        scorer.macroMUC.macroAppend(muc)
        scorer.macroPW.macroAppend(pw)
        scorer.microB3.microAppend(b3)
        scorer.microMUC.microAppend(muc)
        scorer.microPW.microAppend(pw)
      }
    }
    def runParallel(ins: Seq[Document]) = cc.factorie.util.Threading.parMap(ins, pool)(map)
    def runSequential(ins: Seq[(Document)]) = ins.map(map)

  }

  def doTest(testDocs: Seq[Document], wn: WordNet, testTrueMaps: Map[String, GenericEntityMap[Mention]], name: String): Double = {
    val scorer = new CorefScorer[Mention]
    object ScorerMutex
    val pool = java.util.concurrent.Executors.newFixedThreadPool(options.numThreads)
    var accuracy = 0.0
    try {
      val tester = new CorefTester(model, scorer, ScorerMutex, testTrueMaps, pool)
      tester.runParallel(testDocs)
      println("-----------------------")
      println("  * Overall scores")
      scorer.printInhouseScore(name)
      accuracy = scorer.microPW.f1
    } finally pool.shutdown()
    accuracy

  }

  def deserialize(stream: DataInputStream) {
    import cc.factorie.util.CubbieConversions._
    val config = options.getConfigHash
    BinarySerializer.deserialize(config, stream)
    options.setConfigHash(config)
    println("deserializing with config:\n" + options.getConfigHash.iterator.map(x => x._1 + " = " + x._2).mkString("\n"))
    //BinarySerializer.deserialize(model, stream)
    model.deserialize(stream)
    model.MentionPairFeaturesDomain.dimensionDomain.freeze()
    println("model weights 1norm = " + model.parameters.oneNorm)
    stream.close()
  }

  def deserialize(filename: String) {
    deserialize(new DataInputStream(new FileInputStream(filename)))
  }

  def serialize(filename: String) {
    import cc.factorie.util.CubbieConversions._
    println("serializing with config:\n" + options.getConfigHash.iterator.map(x => x._1 + " = " + x._2).mkString("\n"))
    val stream = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(filename))))
    BinarySerializer.serialize(options.getConfigHash, stream)
    model.serialize(stream)
    println("model weights 1norm = " + model.parameters.oneNorm)
    stream.close()
  }


}

