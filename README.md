# Logic-Circuits

A Logic circuits simulation using scala and the akka framework. The circuits 
simulated will be an actor system composed of wires and
components both of which are actors. A wire transport a signal which can be
false (low voltage) or true (high voltage). A component, like AND gate, will receives
message though its input wires and outputs the resultant signal using
the output wire(s). The standard gates have the following delays:

* Inverter: 100 ms
* And: 100 ms
* Or: 1000 ms

A Wire actor will allow component actors to subscribe to state changes by sending messages.
A component actor can be subscribe to more than one Wire.

## Design

The following shows the logic circuits that were simulated.

### Inverter

When the Inverter actor is created, it is subscribed to the Wire by using the AddComponent
message. The outside world will declared the wire to any Boolean, for example true. This
triggers a StateChange message to the Inverter by the Wire input0. The Inverter will receive
this change and will adjust to the current of the Wire output0 accordingly. In this case the
Inverter will send false to the Wire output0 since the Wire input0 current is true.

![Image](images/Inverter.png?raw=true "Inverter")

### And

When the And actor is created, it is subscribed to both Wire input0 and input1 by sending
AddComponent message to both wires. The outside world will set the current of both wires by
sending a Boolean to these wires. For example Wire input0 will be true while Wire input1 will
be false. This will trigger two StateChange messages in total to be send to the And actor from
the Wires. The And actor will then send the current as a Boolean to Wire output0, which in this
case is false. The And actor will send this twice since there are two StateChange messages even
though Wire output0 will remain false for both StateChange.

![Image](images/And.png?raw=true "And")


### Or

The Or actor is very similar to the And actor. The difference is the logic from to Or actor to the
Wire output0. Both Wire input0 and input1 are subscribed to the Or actor using the
AddComponent. The outside world will set the Boolean for both wires, for example Wire
input0 will be true while Wire input1 will be false. This will triggered a StateChange from each
of the Wires. Or will receive these StateChange and will send two Boolean message to Wire
output0. The final current of Wire output0 will be true in this case. It is to note that Wire output0
will be updated twice and thus if it is subscribed to a probe two outputs will appear. Since this
is concurrent, the output of Wire output0 depends on which StateChange enters first but the
final output will not be different.

![Image](images/Or.png?raw=true "Or")

### OrAlt

The OrAlt actor is made up of different Inverters and And actors. An example of the message
flow will be given to describe the actor system. Consider that the initial state of both Wire
input0 and input1 is false. This sets all the outputs and states for the components to the correct
current throughout the whole circuit. The outside world changes the current of Wire input0
from false to true. As mentioned above, the Inverter and And actors are subscribed to their
corresponding wires when they are created. Wire input0 will therefore send a Statechange to
Inverter. The Inverter will then send false as a message to Wire outputT1. Wire outputT1 will
therefore trigger a StateChange to And actor since the wire was updated. The And actor will
evaluate the new current and send false to Wire outputT3. Wire outputT3 receives the message
that the wire changed current and will send StateChange messages to it subscribed components.
The Inverter that is subscribed to Wire outputT3 will receive the StateChange message. This
makes the Inverter send true as a message to Wire output0.

![Image](images/OrAlt.png?raw=true "OrAlt")

### Half Adder

Consider the initial state of both Wire input0 and input1 to be false and thus setting all the other
components states and wires to their correct current. The outside world changes the current of
Wire input1 from false to true. This triggers StateChange messages to its subscribed
components in this case And and Or actors which split the program. Starting from the And
actor. The And actor re-evaluates the output and send false to Wire outputCarry. Wire
outputCarry sends a StateChange to Inverter. The Inverter will send true to Wire outputT2 since
it is a subscriber. Wire outputT2 updated its current and thus will send a StateChange to And
actor. Finally the And actor will evaluate the StateChange message and send false to Wire
outputSum. Continuing from the Or actor, it get a StateChange from Wire input1 and re-
evaluates the output. Therefore it sends true to Wire outputT1. Wire outputT1 will send a
StateChange to And actor. The And actor will re-evaluates the output and will send false to
Wire outputSum. As can be seen above, the And actor found before the end of the circuit was
passed twice. If the Wire outputSum was subscribed to a Probe, the output will be displayed
twice both indicating false outputSum. It is to note that the last printed output should be taken
as the correct current.

![Image](images/HalfAdder.png?raw=true "Half Adder")

### Full Adder

Once again the initial state of both Wire input0 and input 1 were set to false and thus setting
all the other components states and wires to their correct current. The outside world changes
the current of Wire input0 to true. This makes Wire input0 send a StateChange to Half Adder
component. This will update both output wires of the Half Adder actor. Starting off from the
sumOutput of the Half Adder. The Half Adder actor sends true to the Wire sumOutputT1. This
will cause a StateChange to be send to another Half Adder actor. The sumOutput of this Half
Adder actor will result in the outputSum of the Full Adder actor, in this case true. The
carryOutput of this Half Adder actor will result in false being sent to the Wire CarryOutputT2.
This Wire will trigger a StateChange to be sent to Or. The Or actor will evaluate the output are
send false to the outputCarry of the Full Adder. Continuing with the carryOutput of the first
Half Adder, false will be sent to the Wire CarryOutputT1. This will trigger a StateChange to
be send again to the Or actor. This will evaluate the output and false will be send to the
outputCarry of the full adder. As can be seen again, multiple components were visited more
than once with a single Wire input change from the outside World. Therefore the output will
be printed more than once and the last one printed should be taken into account.

![Image](images/FullAdder.png?raw=true "Full Adder")

### De-multiplexer With 2 output wires

Consider both Wire control and input were set to false and thus setting all the other components
states and wires to their correct current. The Wire input was set to true by the outside. This will
trigger two StateChange to two different And actors. The first And actor on the right of the
above diagram will receive a StateChange and evaluates to true because as it was mentions, the
components states were set to their correct current. Thus Wire output0 will receive true as a
message. On the other hand, the second And actor will also receive a StateChange. The And
actor will re-evaluate the output and send false to the Wire output1.

![Image](images/Demux2.png?raw=true "Demux 2")

### De-multiplexer

To implement a Demux several Demux2 were used. The amount of Demux2 components
depended on the number of control wires (n) and number of output wires (2 n ). In the Demux
actor, if the control has only 1 element in the list and 2 output wires, it uses a Demux2 to
evaluate the output and stops there. Otherwise, 2 wires are created that will contain the output
of using a Demux2. A Demux2 is created using the input and the head control wire in the
controls list. Following this, 2 new Demux can be created. The tail of the controls goes on both
of the Demux and each Demux gets half of the wires found in the outputs list. For input, each
Demux takes an output wire from the Demux2. This is repeated until each Demux has 1 control
wire and 2 output wires. The following shows the general actor system and a 1-4 Demux
example. It is to note that the general actor system diagram is for 1-4 Demux or greater output.
For 1-2 Demux, Demux2 actor system diagram can be used.

![Image](images/Demux.png?raw=true "Demux")

## Build

sbt was used as the build tool. 

## License

This project is licensed under MIT.
