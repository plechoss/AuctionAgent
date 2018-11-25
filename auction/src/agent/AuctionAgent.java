package agent;

//the list of imports
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import logist.agent.Agent;
import logist.behavior.AuctionBehavior;
import logist.plan.Action;
import logist.plan.Action.Delivery;
import logist.plan.Action.Pickup;
import logist.plan.Plan;
import logist.simulation.Vehicle;
import logist.task.Task;
import logist.task.TaskDistribution;
import logist.task.TaskSet;
import logist.topology.Topology;
import logist.topology.Topology.City;

/**
 * A very simple auction agent that assigns all tasks to its first vehicle and
 * handles them sequentially.
 * 
 */
//@SuppressWarnings("unused")
public class AuctionAgent implements AuctionBehavior {

	private Topology topology;
	private TaskDistribution distribution;
	private Agent agent;
	private Random random;
	private Vehicle vehicle;
	private List<Vehicle> vehicles;

	private City currentCity;
	private ArrayList<Task> currentTasks = new ArrayList<Task>();
	private CentralizedSolution currentSolution;
	private double currentCost = 0;

	private Random rand = new Random(8);
	private static final double PROB_CHOOSE_OLD = 0.4;

	@Override
	public void setup(Topology topology, TaskDistribution distribution, Agent agent) {

		this.topology = topology;
		this.distribution = distribution;
		this.agent = agent;
		this.vehicle = agent.vehicles().get(0);
		this.currentCity = vehicle.homeCity();

		this.vehicles = agent.vehicles();

		long seed = -9019554669489983951L * currentCity.hashCode() * agent.id();
		this.random = new Random(seed);
	}

	@Override
	public void auctionResult(Task previous, int winner, Long[] bids) {
		if (winner == agent.id()) {
			addTask(previous);
		} else {
			// put the strategy for adjusting here
		}
	}

	@Override
	public Long askPrice(Task task) {

		for (Vehicle v : vehicles) {
			if (v.capacity() < task.weight)
				return null;

		}

		double marginalCost = marginalCost(task);

		double ratio = 1.0 + (random.nextDouble() * 0.05 * task.id);
		double bid = ratio * marginalCost;

		return (long) Math.round(bid);
	}

	void addTask(Task task) {
		currentTasks.add(task);
		Task[] tasks = currentTasks.toArray(new Task[currentTasks.size()]);
		TaskSet tempTaskSet = TaskSet.create(tasks);
		currentSolution = slsAlgorithm(vehicles, tempTaskSet);
		currentCost = currentSolution.cost();
		// currentCity = task.deliveryCity;
	}

	double marginalCost(Task task) {
		double marginalCost = Double.MAX_VALUE;
		TaskSet tmpTaskSet;
		if (currentTasks.isEmpty()) {
			Task[] tasks = { task };
			tmpTaskSet = TaskSet.create(tasks);
		} else {
			Task[] tasks = currentTasks.toArray(new Task[currentTasks.size() + 1]);
			tasks[currentTasks.size()] = task;
			tmpTaskSet = TaskSet.create(tasks);
		}
		CentralizedSolution newSolution = slsAlgorithm(vehicles, tmpTaskSet);

		double newCost = newSolution.cost();
		marginalCost = newCost - currentCost;

		return marginalCost;
	}

	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {

//		System.out.println("Agent " + agent.id() + " has tasks " + tasks);
		long time_start = System.currentTimeMillis();
		currentSolution = slsAlgorithm(vehicles, tasks);
		if (currentSolution == null) {
			return new ArrayList<Plan>();
		}
		List<Plan> plans = new ArrayList<Plan>();
		for (int vehicleIdx = 0; vehicleIdx < vehicles.size(); vehicleIdx++) {
			Vehicle vehicle = vehicles.get(vehicleIdx);
			City currentCity = vehicle.getCurrentCity();
			Plan plan = new Plan(currentCity);

			Integer action = currentSolution.nextAction(currentSolution.vehicleAction(vehicleIdx));
			if (action == null) {
				plans.add(Plan.EMPTY);
			} else {
				while (action != null) {
					City nextCity = currentSolution.getCity(action);
					// move: current city => pickup location
					for (City city : currentCity.pathTo(nextCity)) {
						plan.appendMove(city);
					}
					plan.append(currentSolution.materializeAction(action));
					currentCity = nextCity;
					action = currentSolution.nextAction(action);
				}
				plans.add(plan);
			}
		}
		long time_end = System.currentTimeMillis();
		long duration = time_end - time_start;
		System.out.println("The plan was generated in " + duration + " milliseconds.");

		return plans;

	}

	///////////////////////////////

	public class CentralizedSolution {
		private Integer[] nextAction, timeAction, vehicles;
		private int numberOfTasks;
		private List<Task> tasks;

		Integer getNumberOfTasks() {
			return numberOfTasks;
		}

		Integer nextAction(Integer action) {
			// System.out.printf("%d\n",action);
			return nextAction[action];
		}

		Integer time(Integer action) {
			return timeAction[action];
		}

		Integer deliveryTime(Integer pickup) {
			return timeAction[pickup + numberOfTasks];
		}

		Integer pickupTime(Integer delivery) {
			return timeAction[delivery - numberOfTasks];
		}

		Integer vehicle(Integer action) {
			return vehicles[action];
		}

		void setVehicle(Integer vehicle, Integer action) {
			vehicles[action] = vehicle;
		}

		void addAction(Integer current, Integer next) {
			nextAction[current] = next;
		}

		void addTime(Integer action, int t) {
			timeAction[action] = t;
		}

		int pickupAction(Integer taskIdx) {
			return taskIdx;
		}

		int deliveryAction(Integer taskIdx) {
			return taskIdx + numberOfTasks;
		}

		int vehicleAction(Integer vehicleIdx) {
			return vehicleIdx + 2 * numberOfTasks;
		}

		private void getTasks(TaskSet taskSet) {
			tasks = new LinkedList<Task>();
			Iterator<Task> it = taskSet.iterator();
			while (it.hasNext()) {
				tasks.add(it.next());
			}
		}

		private Vehicle materializeVehicle(Integer vehicleIdx) {
			return agent.vehicles().get(vehicleIdx);
		}

		private double dist(Integer action1, Integer action2) {

			if (action2 == null) {
				return 0;
			}
			return getCity(action1).distanceTo(getCity(action2));
		}

		double cost() {
			double cost = 0;
			for (int taskIdx = 0; taskIdx < numberOfTasks; taskIdx++) {
				Integer pickup = pickupAction(taskIdx);
				Integer delivery = deliveryAction(taskIdx);
				int vehicleCost = materializeVehicle(vehicle(pickup)).costPerKm();
				cost += dist(pickup, nextAction(pickup)) * vehicleCost;
				cost += dist(delivery, nextAction(delivery)) * vehicleCost;
			}
			return cost;
		}

		public CentralizedSolution(TaskSet taskSet) {
			getTasks(taskSet);
			numberOfTasks = tasks.size();
			if (numberOfTasks == 0)
				return;

			nextAction = new Integer[agent.vehicles().size() + numberOfTasks * 2];
			timeAction = new Integer[numberOfTasks * 2];
			vehicles = new Integer[numberOfTasks * 2];

			System.out.println("Running selectInitialSolution");
			List<Vehicle> agentVehicles = agent.vehicles();
			int biggest = 0;
			Vehicle biggestVehicle = agentVehicles.get(0);
			for (int i = 0; i < agentVehicles.size(); i++) {
				Vehicle vhcl = agentVehicles.get(i);
				if (vhcl.capacity() > biggestVehicle.capacity()) {
					biggestVehicle = vhcl;
					biggest = i;
				}
			}

			int prevAction = 0;
			int counter = 0;
			addAction(vehicleAction(biggest), pickupAction(0));
			setVehicle(biggest, pickupAction(0));
			for (int taskIdx = 0; taskIdx < tasks.size(); taskIdx++) {
				if (tasks.get(taskIdx).weight > biggestVehicle.capacity()) {
					throw new Error("Task weight exceeds the biggest vehicle capacity");
				}
				// Set up pickup
				setVehicle(biggest, pickupAction(taskIdx));
				addTime(pickupAction(taskIdx), counter++);
				addAction(prevAction, pickupAction(taskIdx));
				// Set up delivery
				setVehicle(biggest, deliveryAction(taskIdx));
				addTime(deliveryAction(taskIdx), counter++);
				addAction(pickupAction(taskIdx), deliveryAction(taskIdx));
				prevAction = deliveryAction(taskIdx);
			}
		}

		Integer taskFromAction(Integer action) {
			return action % numberOfTasks;
		}

		City getCity(Integer action) {
			if (action == null)
				return null;
			Task task;
			if (action < numberOfTasks) {
				task = tasks.get(action);
				if (task != null) {
					return task.pickupCity;
				}
			} else {
				task = tasks.get(action - numberOfTasks);
				if (task != null) {
					return task.deliveryCity;
				}
			}
			return null;
		}

		Task getTask(Integer action) {
			return tasks.get(taskFromAction(action));
		}

		Action materializeAction(Integer action) {
			if (action < numberOfTasks) {
				return new Pickup(tasks.get(action));
			}
			return new Delivery(tasks.get(action - numberOfTasks));
		}

		boolean isPickupAction(Integer action) {
			return action < numberOfTasks;
		}

		boolean isDeliveryAction(Integer action) {
			return action >= numberOfTasks && action < 2 * numberOfTasks;
		}

		boolean sameTypeActions(Integer action1, Integer action2) {
			return (isDeliveryAction(action1) && isDeliveryAction(action2))
					|| (isPickupAction(action1) && isPickupAction(action2));
		}

		Integer findPrecedingAction(Integer vehicle, Integer action) {
			Integer predAction = null;
			Integer currentAction = vehicleAction(vehicle);
			while (!currentAction.equals(action)) {
				predAction = currentAction;
				currentAction = nextAction(currentAction);
			}
			return predAction;
		}

		CentralizedSolution(CentralizedSolution cs) {
			this.nextAction = Arrays.copyOf(cs.nextAction, cs.nextAction.length);
			this.timeAction = Arrays.copyOf(cs.timeAction, cs.timeAction.length);
			this.vehicles = Arrays.copyOf(cs.vehicles, cs.vehicles.length);
			this.numberOfTasks = cs.numberOfTasks;
			this.tasks = cs.tasks;
		}

		boolean isCorrect() {
			for (int vehicleIdx = 0; vehicleIdx < agent.vehicles().size(); vehicleIdx++) {
				int load = 0;
				Integer currentAction = nextAction(vehicleAction(vehicleIdx));
				while (currentAction != null) {
					Integer taskIdx = taskFromAction(currentAction);
					if (deliveryTime(pickupAction(taskIdx)) <= pickupTime(deliveryAction(taskIdx)))
						return false;
					if (isPickupAction(currentAction))
						load += getTask(currentAction).weight;
					else
						load -= getTask(currentAction).weight;
					if (load > materializeVehicle(vehicleIdx).capacity())
						return false;
					currentAction = nextAction(currentAction);
				}
			}
			return true;
		}
	}

	private List<CentralizedSolution> chooseNeighbours(CentralizedSolution oldSolution, List<Vehicle> vehicles,
			TaskSet tasks) {
		// System.out.println("Running chooseNeighbours");
		List<CentralizedSolution> solutions = new LinkedList<CentralizedSolution>(); // TODO:
		int randVehicleIdx = Math.abs(rand.nextInt()) % vehicles.size();
		while (oldSolution.nextAction(oldSolution.vehicleAction(randVehicleIdx)) == null) {
			randVehicleIdx = Math.abs(rand.nextInt()) % vehicles.size();
		}

		for (int vehicleIdx = 0; vehicleIdx < vehicles.size(); vehicleIdx++) {
			if (vehicleIdx != randVehicleIdx) {
				try {
					CentralizedSolution newSolution = changingVehicle(oldSolution, randVehicleIdx, vehicleIdx);
					if (newSolution.isCorrect()) {
						solutions.add(newSolution);
						System.err.println("change vehicle");
					}
					// System.out.println("Solutions added in chooseNeighbours");
				} catch (Exception e) {
					// System.out.println("Invalid solution");
					continue;
				}
			}

			int length = 0;
			Integer currentAction = oldSolution.nextAction(vehicleIdx);
			while (currentAction != null) {
				currentAction = oldSolution.nextAction(currentAction);
				length++;
			}
			// System.out.println("End of the do while loop in chooseNeighbours");
			if (length > 2) {
				for (int i = 1; i < length; i++) {
					for (int j = i + 1; j < length; j++) {
						try {
							CentralizedSolution newSolution = changeActionOrder(oldSolution, vehicleIdx, i, j);
							if (newSolution.isCorrect()) {
								solutions.add(newSolution);
								System.out.println("success rearrange");
							} else {
								// System.out.println("Invalid solution");
							}
						} catch (Exception e) {
							// System.out.println("Invalid solution");
							continue;
						}
					}
				}
			}
		}

		return solutions;
	}

	private CentralizedSolution changingVehicle(CentralizedSolution oldSolution, Integer oldVehicle, Integer newVehicle)
			throws Exception {
		// Assigns the first treated task from vehicle1 to vehicle2
		System.out.println("Running changingVehicle");
		CentralizedSolution newSolution = new CentralizedSolution(oldSolution);
		Integer pickup = oldSolution.nextAction(newSolution.vehicleAction(oldVehicle));
		Integer delivery = oldSolution.deliveryAction(oldSolution.taskFromAction(pickup));
		Integer newVehicleFirstAction = newSolution.nextAction(newSolution.vehicleAction(newVehicle));
		newSolution.addAction(pickup, delivery);
		newSolution.addAction(delivery, newVehicleFirstAction);
		newSolution.addAction(oldSolution.vehicleAction(newVehicle), pickup);
		newSolution.addAction(oldSolution.vehicleAction(oldVehicle), oldSolution
				.nextAction(oldSolution.nextAction(oldSolution.nextAction(oldSolution.vehicleAction(oldVehicle)))));
		newSolution.setVehicle(newVehicle, pickup);
		newSolution.setVehicle(newVehicle, delivery);
		return newSolution;
	}

	private void updateTime(CentralizedSolution solution, Integer vehicleIdx) {
		// System.out.println("Running updateTime");
		Integer action1 = solution.nextAction(solution.vehicleAction(vehicleIdx));
		if (action1 != null) {
			solution.addTime(action1, 1);
			Integer action2;
			do {
				action2 = solution.nextAction(action1);
				if (action2 != null) {
					solution.addTime(action2, solution.time(action1) + 1);
					action1 = action2;
				}
			} while (action2 != null);
		}
	}

	private CentralizedSolution changeActionOrder(CentralizedSolution oldSolution, Integer vehicle, Integer action1,
			Integer action2) throws Exception {
		// System.out.println("Running changingTaskOrder");
		CentralizedSolution newSolution = new CentralizedSolution(oldSolution);
		if (newSolution.taskFromAction(action1).equals(newSolution.taskFromAction(action2))) {
			if (!newSolution.sameTypeActions(action1, action2)) {
				throw new Exception("Invalid solution");
			}
		} // TODO: else check

		Integer predAction1 = oldSolution.findPrecedingAction(vehicle, action1);
		Integer postAction1 = oldSolution.nextAction(action1);

		Integer predAction2 = oldSolution.findPrecedingAction(vehicle, action2);
		Integer postAction2 = oldSolution.nextAction(action2);

		if (((predAction1 != null) && predAction1.equals(action2)) || action2.equals(postAction1)
				|| ((predAction2 != null) && predAction2.equals(action1)) || action1.equals(postAction2)) {
			throw new Exception();
		}

		if (predAction1 != null) {
			newSolution.addAction(predAction1, action2);
		}

		if (predAction2 != null) {
			newSolution.addAction(predAction2, action1);
		}

		newSolution.addAction(action1, postAction2);
		newSolution.addAction(action2, postAction1);

		updateTime(newSolution, vehicle);
		return newSolution;
	}

	private CentralizedSolution localChoice(List<CentralizedSolution> solutions, CentralizedSolution oldSolution) {
		// System.out.println("Running localChoice");
		CentralizedSolution bestSolution = oldSolution;
		double bestCost = oldSolution.cost();
		solutions.add(oldSolution);

		for (CentralizedSolution solution : solutions) {
			double currentCost = solution.cost();
			if (currentCost <= bestCost) {
				bestSolution = solution;
				bestCost = currentCost;
			}
		}

		List<CentralizedSolution> solutionsNew = new LinkedList<CentralizedSolution>();
		for (CentralizedSolution solution : solutions) {
			double currentCost = solution.cost();
			if (currentCost == bestCost) {
				solutionsNew.add(solution);
			}
		}

		double p = rand.nextDouble();
		if (p < PROB_CHOOSE_OLD) {
			return oldSolution;
		} else {
			Collections.shuffle(solutionsNew);
			return solutionsNew.get(0);
		}
	}

	private CentralizedSolution slsAlgorithm(List<Vehicle> vehicles, TaskSet tasks) {
		System.out.println("Running slsAlgorithm");
		long startTime = System.currentTimeMillis();
		CentralizedSolution solution = new CentralizedSolution(tasks);
		try {
			System.out.println("Running slsAlgorithm try block");
			// int numIterations = 10;
			while (true) {
				CentralizedSolution oldSolution = solution;
				List<CentralizedSolution> solutionSet = chooseNeighbours(oldSolution, vehicles, tasks);
				solution = localChoice(solutionSet, oldSolution);
				long currentTime = System.currentTimeMillis();
				if (currentTime - startTime > 2000) {
					break;
				}
			}
			System.out.printf("Cost: %f\n", solution.cost());
			return solution;
		} catch (Exception e) {
			e.printStackTrace();
			return solution;
		}
	}

}
