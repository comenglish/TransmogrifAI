/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.local

import java.io.File

import com.salesforce.op.stages.impl.classification.BinaryClassificationModelSelector
import com.salesforce.op.stages.impl.classification.BinaryClassificationModelsToTry._
import com.salesforce.op.test.{PassengerSparkFixtureTest, TestCommon}
import com.salesforce.op.utils.spark.RichRow._
import com.salesforce.op.utils.spark.RichDataset._
import com.salesforce.op.{OpParams, OpWorkflow}
import org.junit.runner.RunWith
import org.scalatest.FlatSpec
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class OpWorkflowRunnerLocalTest extends FlatSpec with PassengerSparkFixtureTest with TestCommon {

  val features = Seq(height, weight, gender, description, age).transmogrify()
  val survivedNum = survived.occurs()

  val prediction = BinaryClassificationModelSelector.withTrainValidationSplit(
    splitter = None, modelTypesToUse = Seq(OpLogisticRegression)
  ).setInput(survivedNum, features).getOutput()

  val workflow = new OpWorkflow().setResultFeatures(prediction, survivedNum).setReader(dataReader)

  lazy val model = workflow.train()

  lazy val modelLocation = {
    val path = new File(tempDir + "/op-runner-local-test-model").toString
    model.save(path)
    path
  }

  lazy val rawData = dataReader.generateDataFrame(model.rawFeatures).collect().map(_.toMap)

  lazy val expectedScores = model.score().collect(prediction, survivedNum)

  // TODO: actually test spark wrapped stage with PFA
  Spec(classOf[OpWorkflowRunnerLocal]) should "produce scores without Spark" in {
    val params = new OpParams().withValues(modelLocation = Some(modelLocation))
    val scoreFn = new OpWorkflowRunnerLocal(workflow).score(params)
    val _ = rawData.map(row => scoreFn(row)) // warm up

    val numOfRuns = 1000
    var elapsed = 0L
    for { _ <- 0 until numOfRuns } {
      val start = System.currentTimeMillis()
      val scores = rawData.map(row => scoreFn(row))
      elapsed += System.currentTimeMillis() - start
      for {
        (score, (predV, survivedV)) <- scores.zip(expectedScores)
        expected = Map(
          prediction.name -> predV.value,
          survivedNum.name -> survivedV.value.get
        )
      } score shouldBe expected
    }
    println(s"Scored ${expectedScores.length * numOfRuns} records in ${elapsed}ms")
    println(s"Average time per record: ${elapsed.toDouble / (expectedScores.length * numOfRuns)}ms")
  }

}
