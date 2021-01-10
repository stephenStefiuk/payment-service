package com.techelevator.tenmo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.techelevator.tenmo.models.User;
import com.techelevator.tenmo.models.AuthenticatedUser;
import com.techelevator.tenmo.models.Transfers;
import com.techelevator.tenmo.models.UserCredentials;
import com.techelevator.tenmo.services.AuthenticationService;
import com.techelevator.tenmo.services.AuthenticationServiceException;
import com.techelevator.view.ConsoleService;

public class App {

	private static final String API_BASE_URL = "http://localhost:8080/";

	private static final String[] PENDING_MENU_OPTIONS = {"Cancel", "Approve", "Reject"};
	private static final String MENU_OPTION_EXIT = "Exit";
	private static final String LOGIN_MENU_OPTION_REGISTER = "Register";
	private static final String LOGIN_MENU_OPTION_LOGIN = "Login";
	private static final String[] LOGIN_MENU_OPTIONS = { LOGIN_MENU_OPTION_REGISTER, LOGIN_MENU_OPTION_LOGIN,
			MENU_OPTION_EXIT };
	private static final String MAIN_MENU_OPTION_VIEW_BALANCE = "View your current balance";
	private static final String MAIN_MENU_OPTION_SEND_BUCKS = "Send TE bucks";
	private static final String MAIN_MENU_OPTION_VIEW_PAST_TRANSFERS = "View your past transfers";
	private static final String MAIN_MENU_OPTION_REQUEST_BUCKS = "Request TE bucks";
	private static final String MAIN_MENU_OPTION_VIEW_PENDING_REQUESTS = "View your pending requests";
	private static final String MAIN_MENU_OPTION_LOGIN = "Login as different user";
	private static final String[] MAIN_MENU_OPTIONS = { MAIN_MENU_OPTION_VIEW_BALANCE, MAIN_MENU_OPTION_SEND_BUCKS,
			MAIN_MENU_OPTION_VIEW_PAST_TRANSFERS, MAIN_MENU_OPTION_REQUEST_BUCKS,
			MAIN_MENU_OPTION_VIEW_PENDING_REQUESTS, MAIN_MENU_OPTION_LOGIN, MENU_OPTION_EXIT };

	private AuthenticatedUser currentUser;
	private ConsoleService console;
	private AuthenticationService authenticationService;
	private RestTemplate rest = new RestTemplate();

	public static void main(String[] args) {
		App app = new App(new ConsoleService(System.in, System.out), new AuthenticationService(API_BASE_URL));
		app.run();
	}

	public App(ConsoleService console, AuthenticationService authenticationService) {
		this.console = console;
		this.authenticationService = authenticationService;
	}

	public void run() {
		System.out.println("*********************");
		System.out.println("* Welcome to TEnmo! *");
		System.out.println("*********************");

		registerAndLogin();
		mainMenu();
	}

	private void mainMenu() {
		while (true) {
			String choice = (String) console.getChoiceFromOptions(MAIN_MENU_OPTIONS);
			if (MAIN_MENU_OPTION_VIEW_BALANCE.equals(choice)) {
				viewCurrentBalance();
			} else if (MAIN_MENU_OPTION_VIEW_PAST_TRANSFERS.equals(choice)) {
				viewTransferHistory();
			} else if (MAIN_MENU_OPTION_VIEW_PENDING_REQUESTS.equals(choice)) {
				viewPendingRequests();
			} else if (MAIN_MENU_OPTION_SEND_BUCKS.equals(choice)) {
				sendBucks();
			} else if (MAIN_MENU_OPTION_REQUEST_BUCKS.equals(choice)) {
				requestBucks();
			} else if (MAIN_MENU_OPTION_LOGIN.equals(choice)) {
				login();
			} else {
				// the only other option on the main menu is to exit
				exitProgram();
			}
		}
	}
	
	public void viewCurrentBalance() {
		System.out.println("--------------------------------------------------");
		System.out.println("Your current balance is: $" + getBalance());
		System.out.println("--------------------------------------------------");
	}

	public void viewTransferHistory() {
		Transfers[] allTransfers = rest.exchange(API_BASE_URL + "tenmo/users/transfers/history", 
				HttpMethod.GET, makeAuthEntity(), Transfers[].class).getBody();

		if (allTransfers == null || allTransfers.length < 1) {
			System.out.println("--------------------------------------------------");
			System.out.println("You have no transaction history.");
			System.out.println("--------------------------------------------------");
		}
		else {
			System.out.println("--------------------------------------------------");
			System.out.println("Transfers");
			System.out.println("ID\t\tFrom/To\t\t\tAmount");
			System.out.println("--------------------------------------------------");

			Transfers choice = chooseTransaction(allTransfers);
			
			if (choice != null) {
				getTransactionDetails(choice);
			}
		}
	}

	public void viewPendingRequests() {

		Transfers[] pendingRequests = rest.exchange(API_BASE_URL + "tenmo/users/transfers/pending", 
				HttpMethod.GET, makeAuthEntity(), Transfers[].class).getBody();
		
		if (pendingRequests == null || pendingRequests.length < 1) {
			System.out.println("--------------------------------------------------");
			System.out.println("You have no pending transfers.");
			System.out.println("--------------------------------------------------");
		}
		else {
			System.out.println("--------------------------------------------------");
			System.out.println("Pending Transfers");
			System.out.println("ID\t\tFrom/To\t\t\tAmount");
			System.out.println("--------------------------------------------------");

			Transfers choice = chooseTransaction(pendingRequests);
			getTransactionDetails(choice);
			Long primaryUser = currentUser.getUser().getId().longValue();
			
			if (choice.getSecondaryId() == primaryUser) {
				Long statusId = null;
				String statusDesc = "";
				
				while (statusId == null) {
					try {
						String userChoice = (String) console.getChoiceFromOptions(PENDING_MENU_OPTIONS);
						
						if (userChoice.equals("Approve")) {
							if (choice.getAmount() > getBalance()) {
								throw new IllegalArgumentException("Insufficent funds");
							}
							statusId = 2L;
							statusDesc = "Approved";
						}
						else if (userChoice.equals("Reject")) {
							statusId = 3L;
							statusDesc = "Rejected";
						}
						else {
							statusId = 0L;
						}
					}
					catch (IllegalArgumentException ex) {
						System.out.println(ex.getMessage());
					}
				}
				
				if (statusId != 0L) {
					choice.setStatusId(statusId);
					choice.setStatusDesc(statusDesc);
					createRequest(choice);
				}
			}
		}
	}

	public void sendBucks() {

		User secondary = chooseUser();
		
		if (secondary != null) {
			
			User primary = currentUser.getUser();
			Double transferAmount = null;
			
			while (transferAmount == null) {
				try {
					double userAmount = askAmount();
					
					if (userAmount > getBalance()) {
						throw new IllegalArgumentException("\nInsufficent funds");
					}
					transferAmount = userAmount;
				} 
				catch (IllegalArgumentException ex) {
					System.out.println(ex.getMessage());
				}
			}
			Transfers sendRequest = new Transfers(2L, 2L, (long)primary.getId(), primary.getUsername(), 
					(long)secondary.getId(), secondary.getUsername(), transferAmount);
			
			createRequest(sendRequest);
		}
	}

	public void requestBucks() {
		
		User secondary = chooseUser();
		
		if (secondary != null) {
			
			User primary = currentUser.getUser();
			double requestAmount = askAmount();
			
			Transfers sendRequest = new Transfers(1L, 1L, (long)primary.getId(), primary.getUsername(), 
					(long)secondary.getId(), secondary.getUsername(), requestAmount);
			
			createRequest(sendRequest);
		}
	}
	
	private double getBalance() {
		return rest.exchange(API_BASE_URL + "tenmo/get-balance", HttpMethod.GET, makeAuthEntity(), Double.class).getBody();
	}
	
	private double askAmount() {
		
		Double amount = null;
		
		while (amount == null) {
			try {
				Double userAmount = Double.parseDouble(console.getUserInput("Enter amount"));

				if (userAmount <= 0) {
					throw new IllegalArgumentException("\nPlease enter a positive number");
				}
				amount = userAmount;
			} catch (NumberFormatException e) {
				System.out.println("\nPlease enter a valid amount");
			} catch (IllegalArgumentException e) {
				System.out.println(e.getMessage());
			}
		}
		return amount;
	}
	
	private User chooseUser() {
		
		User[] allUsers = rest.exchange(API_BASE_URL + "tenmo/get-all", HttpMethod.GET, makeAuthEntity(), User[].class).getBody();
		
		if (allUsers == null || allUsers.length < 1) {
			System.out.println("--------------------------------------------------");
			System.out.println("There are no availables users.");
			System.out.println("--------------------------------------------------");
			return null;
		}

		System.out.println("--------------------------------------------------");
		System.out.println("Users");
		System.out.println("ID\t\tName");
		System.out.println("--------------------------------------------------");
	
		Map<Integer, Object> usersMap = new HashMap<Integer, Object>();
		
		for (User user : allUsers) {
			System.out.println(user);		
			usersMap.put(user.getId(), user);
		}
		String prompt = "\nEnter ID of user (0 to cancel)";
		return (User) usersMap.get(chooseId(usersMap, prompt));
	}
	
	private Transfers chooseTransaction(Transfers[] transact) {
		
		Map<Integer, Object> transfersMap = new HashMap<Integer, Object>();
		String username = currentUser.getUser().getUsername();
		
		for (Transfers tran : transact) {
			System.out.println(tran.toString(username));			
			transfersMap.put(Math.toIntExact(tran.getTransferId()), tran);
		}
	
		String prompt = "\nPlease enter ID to view details (0 to cancel)";
		return (Transfers)transfersMap.get(chooseId(transfersMap, prompt));
	}
	
	private void createRequest(Transfers request) {
		
		Transfers fullfilled = null;
		try {
			fullfilled = rest.exchange(API_BASE_URL + "tenmo/transfer-request", HttpMethod.POST, 
					makeTransferEntity(request), Transfers.class).getBody();
		} catch (RestClientResponseException ex) {
			System.out.println(ex.getRawStatusCode() + " : " + ex.getMessage());
		} catch (ResourceAccessException ex) {
			System.out.println(ex.getMessage());
		}
		getTransactionDetails(fullfilled);
	}
	
	private Integer chooseId(Map<Integer, Object> genMap, String prompt) {
		
		Integer choice = null;
		while (choice == null) {
			Integer temp = console.getUserInputInteger(prompt);
			if (temp == 0) {
				return null;
			} else if (!genMap.containsKey(temp)) {
				System.out.println("\nInvalid ID, please try again");
				continue;
			}
			choice = temp;
		}
		return choice;
	}

	private void getTransactionDetails(Transfers transfer) {
		
		System.out.println("\n--------------------------------------------------");
		System.out.println("Transaction Details");
		System.out.println("--------------------------------------------------");
		
		String amount = String.format("%.2f", transfer.getAmount());
		String transferDetails = "Id: " + transfer.getTransferId() + "\n" +
				"From: " + transfer.getPrimaryUsername() + "\n" +
				"To: " + transfer.getSecondaryUsername() + "\n" +
				"Type: " + transfer.getTypeDesc() + "\n" +
				"Status: " + transfer.getStatusDesc() + "\n" +
				"Amount: $" + amount;
		
		System.out.println(transferDetails);
		System.out.println("--------------------------------------------------");
	}
	
	private HttpEntity<Transfers> makeTransferEntity(Transfers transfer) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(currentUser.getToken());
		HttpEntity<Transfers> entity = new HttpEntity(transfer, headers);
		return entity;
	}

	private HttpEntity makeAuthEntity() {

		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(currentUser.getToken());
		return new HttpEntity(headers);
	}

	private void exitProgram() {
		System.exit(0);
	}

	private void registerAndLogin() {
		while (!isAuthenticated()) {
			String choice = (String) console.getChoiceFromOptions(LOGIN_MENU_OPTIONS);
			if (LOGIN_MENU_OPTION_LOGIN.equals(choice)) {
				login();
			} else if (LOGIN_MENU_OPTION_REGISTER.equals(choice)) {
				register();
			} else {
				// the only other option on the login menu is to exit
				exitProgram();
			}
		}
	}

	private boolean isAuthenticated() {
		return currentUser != null;
	}

	private void register() {
		System.out.println("Please register a new user account");
		boolean isRegistered = false;
		while (!isRegistered) // will keep looping until user is registered
		{
			UserCredentials credentials = collectUserCredentials();
			try {
				authenticationService.register(credentials);
				isRegistered = true;
				System.out.println("Registration successful. You can now login.");
			} catch (AuthenticationServiceException e) {
				System.out.println("REGISTRATION ERROR: " + e.getMessage());
				System.out.println("Please attempt to register again.");
			}
		}
	}

	private void login() {
		System.out.println("Please log in");
		currentUser = null;
		while (currentUser == null) // will keep looping until user is logged in
		{
			UserCredentials credentials = collectUserCredentials();
			try {
				currentUser = authenticationService.login(credentials);
			} catch (AuthenticationServiceException e) {
				System.out.println("LOGIN ERROR: " + e.getMessage());
				System.out.println("Please attempt to login again.");
			}
		}
	}

	private UserCredentials collectUserCredentials() {
		String username = console.getUserInput("Username");
		String password = console.getUserInput("Password");
		return new UserCredentials(username, password);
	}
}
