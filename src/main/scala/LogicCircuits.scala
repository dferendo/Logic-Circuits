import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}

import scala.concurrent.duration._

/**
  * Created by dylan on 19/12/2016.
  */
trait SimConfig {
  val inverterDelay = 100 milliseconds
  val andDelay = 100 milliseconds
  val orDelay = 1000 milliseconds
}

case class AddComponent(wireName: String, actor: ActorRef)
case class StateChange(wireName: String, state: Boolean)

class Wire(var currentState: Boolean) extends Actor with ActorLogging {
  var associations = Map[ActorRef, String]()

  def receive: Actor.Receive = {
    case AddComponent(name: String, b: ActorRef) =>
      associations += b -> name
    case current: Boolean =>
      currentState = current
      // Inform others of the current change
      associations.foreach { tuple =>
        tuple._1 ! StateChange(tuple._2, currentState)
      }
  }
}

class Probe(name: String, input0: ActorRef) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher

  // Add itself to be notified when state changes
  input0 ! AddComponent(name, self)

  def receive = {
    // To receive these updates, Probe needs to be added to component in Wire
    case StateChange(name: String, state: Boolean) =>
      log.info("WireName: " + name + " had a StateChange to: " + state)
  }
}

class Inverter(input0: ActorRef, output0: ActorRef) extends Actor with ActorLogging with SimConfig {
  implicit val ec = context.dispatcher
  val wireName = "InverterWire"
  // Add self to check for state change
  input0 ! AddComponent(wireName, self)

  var currentInverterState = false

  def receive = {
    // Backtick is used for styling conventions
    case StateChange(`wireName`, newState: Boolean) =>
      currentInverterState = newState
      context.system.scheduler.scheduleOnce(inverterDelay) {
        // Not gate
        output0 ! (!newState)
      }
  }
}

class And(input0: ActorRef, input1: ActorRef, output0: ActorRef) extends Actor with ActorLogging with SimConfig {
  implicit val ec = context.dispatcher
  val wireName1 = "AndWire1"
  val wireName2 = "AndWire2"

  // Add the two wires to check for state change
  input0 ! AddComponent(wireName1, self)
  input1 ! AddComponent(wireName2, self)

  var currentAndStateWire1 = false
  var currentAndStateWire2 = false

  def receive = {
    case StateChange(`wireName1`, newState: Boolean) =>
      currentAndStateWire1 = newState
      context.system.scheduler.scheduleOnce(andDelay) {
        output0 ! (currentAndStateWire1 && currentAndStateWire2)
      }

    case StateChange(`wireName2`, newState: Boolean) =>
      currentAndStateWire2 = newState
      context.system.scheduler.scheduleOnce(andDelay) {
        output0 ! (currentAndStateWire1 && currentAndStateWire2)
      }
  }
}

class Or(input0: ActorRef, input1: ActorRef, output0: ActorRef) extends Actor with ActorLogging with SimConfig {
  implicit val ec = context.dispatcher
  val wireName1 = "OrWire1"
  val wireName2 = "OrWire2"

  input0 ! AddComponent(wireName1, self)
  input1 ! AddComponent(wireName2, self)

  var currentOrStateWire1 = false
  var currentOrStateWire2 = false

  def receive = {
    case StateChange(`wireName1`, newState: Boolean) =>
      currentOrStateWire1 = newState
      context.system.scheduler.scheduleOnce(andDelay) {
        output0 ! (currentOrStateWire1 || currentOrStateWire2)
      }

    case StateChange(`wireName2`, newState: Boolean) =>
      currentOrStateWire2 = newState
      context.system.scheduler.scheduleOnce(andDelay) {
        output0 ! (currentOrStateWire1 || currentOrStateWire2)
      }
  }
}

class OrAlt(input0: ActorRef, input1: ActorRef, output0: ActorRef) extends Actor with ActorLogging {
  // OrAlt -> Not (Not A And Not B)
  // Create output wire for the Not A, Not B and And
  val outputNotA: ActorRef = context.actorOf(Props(new Wire(false)), "OrAltOutput1")
  val outputNotB: ActorRef = context.actorOf(Props(new Wire(false)), "OrAltOutput2")
  val outputAnd: ActorRef = context.actorOf(Props(new Wire(false)), "OrAltOutput3")

  // Part 1 - Not A
  context.actorOf(Props(new Inverter(input0, outputNotA)), "OrAltNotA")
  // Part 2 - Not B
  context.actorOf(Props(new Inverter(input1, outputNotB)), "OrAltNotB")
  // Part 3 - And. Gets the output of both Inverter as input
  context.actorOf(Props(new And(outputNotA, outputNotB, outputAnd)), "OrAltAAndB")
  // Part 4 - Not And. Get output from And and Not it
  context.actorOf(Props(new Inverter(outputAnd, output0)), "OrAltFinal")

  def receive: Actor.Receive = Actor.emptyBehavior
}

class HalfAdder(input0: ActorRef, input1: ActorRef, outputSum: ActorRef,
                outputCarry: ActorRef) extends Actor with ActorLogging {
  // The carry is calculated A And B
  // The sum is calculated by XOR gate (A or B) and not(A and B)
  val outputAOrB: ActorRef = context.actorOf(Props(new Wire(false)), "HalfAdderOutput1")
  val outputAAndB: ActorRef = context.actorOf(Props(new Wire(false)), "HalfAdderOutput2")
  val outputNotAAndB: ActorRef = context.actorOf(Props(new Wire(false)), "HalfAdderOutput3")

  // Part 1 - Calculate carry
  context.actorOf(Props(new And(input0, input1, outputCarry)), "HalfAdderCarry")
  // Part 2 - Calculate Sum, A or B
  context.actorOf(Props(new Or(input0, input1, outputAOrB)), "HalfAdderAOrB")
  // Not (A and B)
  context.actorOf(Props(new Inverter(outputCarry, outputNotAAndB)), "HalfAdderNotAAndB")
  // (A or B) and not(A and B)
  context.actorOf(Props(new And(outputAOrB, outputNotAAndB, outputSum)), "HalfAdderFinal")
  def receive: Actor.Receive = Actor.emptyBehavior
}

class FullAdder(input0: ActorRef, input1: ActorRef, inputCarry: ActorRef, outputSum: ActorRef,
                outputCarry: ActorRef)  extends Actor with ActorLogging {
  // Full adder can be constructed with 2 half adders followed by Or gate
  val outputFirstHalfAdderCarry: ActorRef = context.actorOf(Props(new Wire(false)), "FullAdderOutput1")
  val outputFirstHalfAdderSum: ActorRef = context.actorOf(Props(new Wire(false)), "FullAdderOutput2")
  val outputSecondHalfAdderCarry: ActorRef = context.actorOf(Props(new Wire(false)), "FullAdderOutput3")

  // First Half Adder
  context.actorOf(Props(new HalfAdder(input0, input1, outputFirstHalfAdderSum,
    outputFirstHalfAdderCarry)), "FirstHalfAdder")
  // Second Half Adder
  context.actorOf(Props(new HalfAdder(outputFirstHalfAdderSum, inputCarry, outputSum,
    outputSecondHalfAdderCarry)), "SecondHalfAdder")
  // Full Adder
  context.actorOf(Props(new Or(outputFirstHalfAdderCarry, outputSecondHalfAdderCarry,
    outputCarry)), "FullAdderFinal")

  def receive: Actor.Receive = Actor.emptyBehavior
}

class Demux2(input: ActorRef, control: ActorRef, output1: ActorRef,
             output0: ActorRef) extends Actor with ActorLogging {
  // Using 1-to-2 demux logic gates,
  // Output0: (Not control) And Input
  // Output1: Control And Input
  val outputNotControl: ActorRef = context.actorOf(Props(new Wire(false)), "Demux2OutputT1")

  context.actorOf(Props(new Inverter(control, outputNotControl)), "NotControl")
  // Output0
  context.actorOf(Props(new And(input, outputNotControl, output0)), "Demux2Output0")
  // Output1
  context.actorOf(Props(new And(input, control, output1)), "Demux2Output1")

  def receive: Actor.Receive = Actor.emptyBehavior
}

class Demux(input: ActorRef, controls: List[ActorRef],
            outputs: List[ActorRef]) extends Actor with ActorLogging {

  // Should never happen unless not passed parameters
  if (controls.isEmpty)
    log.error("Control wires input is not correct!")
  else if (controls.size == 1 && outputs.size != 2)
    log.error("Output wires input is not correct!")
  // Control only has 2 choices left, can use demux2 to determine result
  else if (controls.size == 1) {
    context.actorOf(Props(new Demux2(input, controls.head, outputs.head, outputs(1))), "Demux2NoMoreSplit")
  }
  else {
    // Demux2 will output 2 wires
    val outputDemux2First = context.actorOf(Props(new Wire(false)))
    val outputDemux2Second = context.actorOf(Props(new Wire(false)))

    // Partition the list into 2 lists, First half, Second half
    val dividedOutput = outputs.splitAt(outputs.length / 2)

    // Use the head of the control to determine the result of the demux2
    context.actorOf(Props(new Demux2(input, controls.head, outputDemux2Second, outputDemux2First)))

    // Create two separable Demux each will contain half controls wires of what their parent had
    context.actorOf(Props(new Demux(outputDemux2Second, controls.tail, dividedOutput._1)))
    context.actorOf(Props(new Demux(outputDemux2First, controls.tail, dividedOutput._2)))
  }
  def receive: Actor.Receive = Actor.emptyBehavior
}

object LogicCircuitMain extends App {
  val system = ActorSystem("LogicCircuit")
  // Thread.sleep is used for testing.
  // Inverter test
  /*
  val wire0 = system.actorOf(Props(new Wire(false)), "Wire0")
  val wire1 = system.actorOf(Props(new Wire(false)), "Wire1")
  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("Output0", wire1)))
  val inv0 = system.actorOf(Props(new Inverter(wire0, wire1)), "Inverter0")

  Thread.sleep(1000)
  println("Not Gate")
  wire0 ! false

  Thread.sleep(1000)
  println()
  wire0 ! true

  Thread.sleep(1000)
  system.terminate()
  */

  // And test
  /*
  val wire0 = system.actorOf(Props(new Wire(false)), "Wire0")
  val wire1 = system.actorOf(Props(new Wire(false)), "Wire1")
  val wire2 = system.actorOf(Props(new Wire(false)), "Wire2")
  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("Input1", wire1)))
  val prob2 = system.actorOf(Props(new Probe("Output0", wire2)))
  val and = system.actorOf(Props(new And(wire0, wire1, wire2)), "And")

  Thread.sleep(1000)
  println("And Gate")
  wire0 ! false
  wire1 ! false

  Thread.sleep(1000)
  println()
  wire0 ! true
  wire1 ! true

  Thread.sleep(1000)
  println()
  wire1 ! false

  Thread.sleep(1000)
  system.terminate()
  */
  // Or Test
  /*
  val wire0 = system.actorOf(Props(new Wire(false)), "Wire0")
  val wire1 = system.actorOf(Props(new Wire(false)), "Wire1")
  val wire2 = system.actorOf(Props(new Wire(false)), "Wire2")
  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("Input1", wire1)))
  val prob2 = system.actorOf(Props(new Probe("Output0", wire2)))
  val and = system.actorOf(Props(new Or(wire0, wire1, wire2)), "Or")

  Thread.sleep(1000)
  println("Or Gate")
  wire0 ! false
  wire1 ! false

  Thread.sleep(1000)
  println()
  wire0 ! true

  Thread.sleep(1000)
  println()
  wire0 ! false

  Thread.sleep(1000)
  println()
  wire1 ! true

  Thread.sleep(1000)
  system.terminate()
  */

  // Or alt Test
  /*
  val wire0 = system.actorOf(Props(new Wire(false)), "Wire0")
  val wire1 = system.actorOf(Props(new Wire(false)), "Wire1")
  val wire2 = system.actorOf(Props(new Wire(false)), "Wire2")
  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("Input1", wire1)))
  val prob2 = system.actorOf(Props(new Probe("Output0", wire2)))
  val and = system.actorOf(Props(new OrAlt(wire0, wire1, wire2)), "OrAlt")

  Thread.sleep(1000)
  println("OrAlt Gate")
  wire0 ! false
  wire1 ! false

  Thread.sleep(1000)
  println()
  wire0 ! true

  Thread.sleep(1000)
  println()
  wire0 ! false

  Thread.sleep(1000)
  println()
  wire1 ! true

  Thread.sleep(1000)
  system.terminate()
  */
  // Half adder test
  /*
  val wire0 = system.actorOf(Props(new Wire(false)), "Wire0")
  val wire1 = system.actorOf(Props(new Wire(false)), "Wire1")
  val wire2 = system.actorOf(Props(new Wire(false)), "Wire2")
  val wire3 = system.actorOf(Props(new Wire(false)), "Wire3")
  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("Input1", wire1)))
  val prob2 = system.actorOf(Props(new Probe("OutputSum", wire2)))
  val prob3 = system.actorOf(Props(new Probe("OutputCarry", wire3)))
  val halfAdder = system.actorOf(Props(new HalfAdder(wire0, wire1, wire2, wire3)))

  Thread.sleep(1000)
  println("HalfAdder")
  wire0 ! false
  wire1 ! false

  Thread.sleep(1000)
  println()
  wire0 ! true

  Thread.sleep(1000)
  println()
  wire1 ! true

  Thread.sleep(1000)
  system.terminate()
  */
  // Full adder
  /*
  val wire0 = system.actorOf(Props(new Wire(false)), "Wire0")
  val wire1 = system.actorOf(Props(new Wire(false)), "Wire1")
  val wire2 = system.actorOf(Props(new Wire(false)), "Wire2")
  val wire3 = system.actorOf(Props(new Wire(false)), "Wire3")
  val wire4 = system.actorOf(Props(new Wire(false)), "Wire4")
  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("Input1", wire1)))
  val prob2 = system.actorOf(Props(new Probe("InputCarry", wire2)))
  val prob3 = system.actorOf(Props(new Probe("OutputSum", wire3)))
  val prob4 = system.actorOf(Props(new Probe("OutputCarry", wire4)))
  val halfAdder = system.actorOf(Props(new FullAdder(wire0, wire1, wire2, wire3, wire4)))

  Thread.sleep(1000)
  println("HalfAdder")
  wire0 ! false
  wire1 ! false
  wire2 ! false

  Thread.sleep(1000)
  println()
  wire0 ! true

  Thread.sleep(1000)
  println()
  wire1 ! true

  Thread.sleep(1000)
  println()
  wire2 ! true

  Thread.sleep(1000)
  system.terminate()
  */
  // Demux2
  /*
  val wire0 = system.actorOf(Props(new Wire(false)), "Wire0")
  val wire1 = system.actorOf(Props(new Wire(false)), "Wire1")
  val wire2 = system.actorOf(Props(new Wire(false)), "Wire2")
  val wire3 = system.actorOf(Props(new Wire(false)), "Wire3")
  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("control0", wire1)))
  val prob2 = system.actorOf(Props(new Probe("Output0", wire2)))
  val prob3 = system.actorOf(Props(new Probe("Output1", wire3)))
  val demux2 = system.actorOf(Props(new Demux2(wire0, wire1, wire3, wire2)), "Demux2")

  Thread.sleep(1000)
  println("Demux2")
  wire0 ! false
  wire1 ! false

  Thread.sleep(1000)
  println()
  wire0 ! true

  Thread.sleep(1000)
  println()
  wire1 ! true

  Thread.sleep(1000)
  println()
  wire0 ! false

  Thread.sleep(1000)
  system.terminate()
  */

  // Demux
  // One to eight
  /*
  val wire0 = system.actorOf(Props(new Wire(false)))
  val wire1 = system.actorOf(Props(new Wire(false)))
  val wire2 = system.actorOf(Props(new Wire(false)))
  val wire3 = system.actorOf(Props(new Wire(false)))
  val wire4 = system.actorOf(Props(new Wire(false)))
  val wire5 = system.actorOf(Props(new Wire(false)))
  val wire6 = system.actorOf(Props(new Wire(false)))
  val wire7 = system.actorOf(Props(new Wire(false)))
  val wire8 = system.actorOf(Props(new Wire(false)))
  val wire9 = system.actorOf(Props(new Wire(false)))
  val wire10 = system.actorOf(Props(new Wire(false)))
  val wire11 = system.actorOf(Props(new Wire(false)))

  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("control0", wire1)))
  val prob2 = system.actorOf(Props(new Probe("control1", wire2)))
  val prob3 = system.actorOf(Props(new Probe("control2", wire3)))
  val prob4 = system.actorOf(Props(new Probe("output0", wire4)))
  val prob5 = system.actorOf(Props(new Probe("output1", wire5)))
  val prob6 = system.actorOf(Props(new Probe("output2", wire6)))
  val prob7 = system.actorOf(Props(new Probe("output3", wire7)))
  val prob8 = system.actorOf(Props(new Probe("output4", wire8)))
  val prob9 = system.actorOf(Props(new Probe("output5", wire9)))
  val prob10 = system.actorOf(Props(new Probe("output6", wire10)))
  val prob11 = system.actorOf(Props(new Probe("output7", wire11)))

  val control = List(wire3, wire2, wire1)
  val outputs = List(wire11, wire10, wire9, wire8, wire7, wire6, wire5, wire4)

  val demux = system.actorOf(Props(new Demux(wire0, control, outputs)))

  Thread.sleep(1000)
  println("Demux")
  wire1 ! false
  wire2 ! false
  wire3 ! false

  Thread.sleep(1000)
  println()
  // output0 should be true
  wire0 ! true

  Thread.sleep(1000)
  println()
  // output1 should be true
  wire1 ! true

  Thread.sleep(1000)
  println()
  // output5 should be true
  wire3 ! true

  Thread.sleep(1000)
  println()
  // output7 should be true
  wire2 ! true

  Thread.sleep(1000)
  println()
  // All outputs should be false
  wire0 ! false

  Thread.sleep(1000)
  system.terminate()
  */

  // Demux 1-4
  /*
  val wire0 = system.actorOf(Props(new Wire(false)), "wire0")
  val wire1 = system.actorOf(Props(new Wire(false)), "wire1")
  val wire2 = system.actorOf(Props(new Wire(false)), "wire2")
  val wire3 = system.actorOf(Props(new Wire(false)), "wire3")
  val wire4 = system.actorOf(Props(new Wire(false)), "wire4")
  val wire5 = system.actorOf(Props(new Wire(false)), "wire5")
  val wire6 = system.actorOf(Props(new Wire(false)), "wire6")

  val prob0 = system.actorOf(Props(new Probe("Input0", wire0)))
  val prob1 = system.actorOf(Props(new Probe("control0", wire1)))
  val prob2 = system.actorOf(Props(new Probe("control1", wire2)))
  val prob3 = system.actorOf(Props(new Probe("output0", wire3)))
  val prob4 = system.actorOf(Props(new Probe("output1", wire4)))
  val prob5 = system.actorOf(Props(new Probe("output2", wire5)))
  val prob6 = system.actorOf(Props(new Probe("output3", wire6)))

  val controls = List(wire1, wire2)
  val outputs = List(wire3, wire4, wire5, wire6)
  val demux = system.actorOf(Props(new Demux(wire0, controls, outputs)))

  Thread.sleep(1000)
  println()
  wire1 ! false
  wire2 ! false

  Thread.sleep(1000)
  println()
  wire0 ! true

  Thread.sleep(1000)
  println()
  wire1 ! true

  Thread.sleep(1000)
  system.terminate()
  */
}